package vn.edu.giavien;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.giavien.data.RestaurantRepository;
import vn.edu.giavien.domain.BookingPolicy;
import vn.edu.giavien.domain.Models.*;
import vn.edu.giavien.service.BookingService;
import vn.edu.giavien.service.TakeawayService;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:giavien-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
      "app.demo=true",
      "app.vnpay.tmn-code=TESTCODE",
      "app.vnpay.hash-secret=test-secret"
    })
@AutoConfigureMockMvc
@Import(BookingIntegrationTest.TimeConfig.class)
class BookingIntegrationTest {
  @Autowired BookingService service;
  @Autowired TakeawayService takeaway;
  @Autowired vn.edu.giavien.service.VnpayPaymentService payments;
  @Autowired vn.edu.giavien.service.VnpayGateway gateway;
  @Autowired RestaurantRepository repo;
  @Autowired MutableClock clock;
  @Autowired MockMvc mvc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired vn.edu.giavien.config.SeedData seed;
  Account customer, staff, admin;
  final LocalDateTime baseline = LocalDateTime.of(2026, 9, 21, 10, 0);

  static class MutableClock extends Clock {
    private final AtomicReference<Instant> instant =
        new AtomicReference<>(Instant.parse("2026-09-21T03:00:00Z"));

    void set(LocalDateTime value) {
      instant.set(value.atZone(getZone()).toInstant());
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("Asia/Ho_Chi_Minh");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
      return instant.get();
    }
  }

  @TestConfiguration
  static class TimeConfig {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  TakeawayService.Input takeawayInput(String name, String phone, LocalDateTime pickup, String method, Map<Long, Integer> items) {
    Map<Long, Long> prices = new java.util.HashMap<>();
    takeaway.quote(items).lines().forEach(line -> prices.put(line.menuItemId(), line.unitPrice()));
    return new TakeawayService.Input(name, phone, pickup, method, items, prices);
  }
  @BeforeEach
  void setup() {
    clock.set(baseline);
    repo.jdbc().update("UPDATE group_promotion SET enabled=FALSE,minimum_guests=4,discount_percent=10 WHERE id=1");
    repo.jdbc().update("UPDATE menu_item SET stock_quantity=NULL,low_stock_threshold=5,available=TRUE");
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              repo.lockSchedule();
              for (String table :
                  List.of(
                      "takeaway_demo_payment",
                      "takeaway_order_line",
                      "takeaway_order",
                      "audit_event",
                      "demo_payment",
                      "payment_attempt",
                      "change_request",
                      "order_line",
                      "reservation_table",
                      "reservation")) repo.jdbc().update("DELETE FROM " + table);
              repo.jdbc()
                  .update(
                      "UPDATE restaurant_settings SET"
                          + " deposit_per_table=100000,hold_minutes=15,cleanup_minutes=15,grace_minutes=15"
                          + " WHERE id=1");
              repo.jdbc().update("UPDATE menu_item SET available=TRUE");
            });
    customer = service.account("khach@moc.local");
    staff = service.account("nhanvien@moc.local");
    admin = service.account("admin@moc.local");
  }

  BookingService.BookingInput input(LocalDateTime start, int guests, List<Long> tables) {
    return new BookingService.BookingInput(
        start, start.plusHours(2), guests, tables, Map.of(1L, 2), "Không cay");
  }

  String create(LocalDateTime start) {
    return service.create(customer, input(start, 2, List.of(1L)));
  }

  String paid(LocalDateTime start) {
    String id = create(start);
    simulateVerifiedPayment(id);
    return id;
  }

  void simulateVerifiedPayment(String id) {
    assertThat(service.gatewayPayment(id, repo.booking(id).orElseThrow().deposit(), "123456", true))
        .isEqualTo("00");
  }

  Map<String, String> urlParameters(String url) {
    var result = new TreeMap<String, String>();
    for (String part : java.net.URI.create(url).getRawQuery().split("&")) {
      String[] pair = part.split("=", 2);
      result.put(
          java.net.URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
          java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
    }
    return result;
  }

  Map<String, String> callbackParameters(String reference, String code, String transaction)
      throws Exception {
    var params = new TreeMap<String, String>();
    params.put("vnp_TmnCode", "TESTCODE");
    params.put("vnp_TxnRef", reference);
    var amounts = repo.jdbc().queryForList(
        "SELECT amount FROM payment_attempt WHERE id=?", Long.class, reference);
    params.put("vnp_Amount", Long.toString((amounts.isEmpty() ? 100000 : amounts.getFirst()) * 100));
    params.put("vnp_ResponseCode", code);
    params.put("vnp_TransactionStatus", code.equals("00") ? "00" : "02");
    params.put("vnp_TransactionNo", transaction);
    params.put("vnp_SecureHash", signature(params));
    return params;
  }

  String startPayment(String id) {
    return urlParameters(payments.start(id, customer, "127.0.0.1")).get("vnp_TxnRef");
  }

  @Test
  void takeawayUsesTheCurrentMenuPriceAndDoesNotCreateAReservation() {
    String id =
        takeaway.create(
            customer,
            takeawayInput(
                "Khách trải nghiệm", "0901234567", baseline.plusMinutes(30), "CASH", Map.of(1L, 2)));
    TakeawayOrder order = repo.takeaway(id).orElseThrow();
    assertThat(order.status()).isEqualTo(TakeawayStatus.WAITING_PICKUP);
    assertThat(order.total())
        .isEqualTo(repo.dishes().stream().filter(d -> d.id() == 1L).findFirst().orElseThrow().price() * 2);
    assertThat(order.lines()).singleElement().satisfies(line -> assertThat(line.quantity()).isEqualTo(2));
    assertThat(repo.bookings(customer.id())).isEmpty();
  }

  @Test
  void takeawayHistoryShowsCashOrdersWithoutRequiringATableBooking() throws Exception {
    String id = takeaway.create(customer,
        takeawayInput("Khách trải nghiệm", "0901234567", baseline.plusMinutes(30), "CASH", Map.of(1L, 3)));
    var order = repo.takeaway(id).orElseThrow();
    String page = mvc.perform(get("/bookings").with(user(customer.email()).roles("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(model().attribute("takeawayOrders", repo.takeawayOrders(customer.id())))
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(page).contains("Đặt đến lấy", order.code(), "/takeaway/orders/" + id,
        "3 × " + order.lines().getFirst().itemName(), "Chờ khách đến lấy", "Tiền mặt khi nhận món");
    assertThat(page).doesNotContain("Chiếc bàn đầu tiên đang đợi bạn.");
    assertThat(repo.bookings(customer.id())).isEmpty();
    mvc.perform(get("/takeaway/orders/" + id).with(user(customer.email()).roles("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("/bookings#takeaway-history")));
  }

  @Test
  void unavailableTransferIsRejectedByTheApiWithoutSavingAnOrder() throws Exception {
    mvc.perform(post("/api/takeaway-orders").with(user(customer.email()).roles("CUSTOMER")).with(csrf())
        .contentType("application/json").content("""
          {"customerName":"Khách hàng","phone":"0901234567","pickupAt":"2026-09-21T10:30:00",
           "paymentMethod":"TRANSFER","items":{"1":1},"expectedUnitPrices":{"1":65000}}
          """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("không khả dụng")));
    assertThat(repo.takeawayOrders(customer.id())).isEmpty();
  }

  @Test
  void changedPriceRequiresAnotherConfirmationAndCannotBeBypassedByOmittingPrices() throws Exception {
    long price = repo.dishes().getFirst().price();
    String body = """
        {"customerName":"Khách hàng","phone":"0901234567","pickupAt":"2026-09-21T10:30:00",
         "paymentMethod":"CASH","items":{"1":2},"expectedUnitPrices":{"1":%d}}
        """;
    mvc.perform(post("/api/takeaway-orders/quote").with(user(customer.email()).roles("CUSTOMER")).with(csrf())
        .contentType("application/json").content("{\"items\":{\"1\":2}}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(price * 2));
    mvc.perform(post("/api/takeaway-orders").with(user(customer.email()).roles("CUSTOMER")).with(csrf())
        .contentType("application/json").content(body.formatted(price - 1000)))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.quote.total").value(price * 2));
    assertThat(repo.takeawayOrders(customer.id())).isEmpty();
    assertThatThrownBy(() -> takeaway.create(customer, new TakeawayService.Input("Khách hàng", "0901234567",
        baseline.plusMinutes(30), "CASH", Map.of(1L, 2), null))).hasMessageContaining("cập nhật giá");
    mvc.perform(post("/api/takeaway-orders").with(user(customer.email()).roles("CUSTOMER")).with(csrf())
        .contentType("application/json").content(body.formatted(price)))
        .andExpect(status().isCreated());
    assertThat(repo.takeawayOrders(customer.id())).singleElement().satisfies(o -> assertThat(o.total()).isEqualTo(price * 2));
  }

  @Test
  void staffCanPrepareDeliverAndCollectCashButCustomersCannot() throws Exception {
    String id = takeaway.create(customer, takeawayInput("Khách hàng", "0901234567", baseline.plusMinutes(30), "CASH", Map.of(1L, 1)));
    String path = "/staff/takeaway-orders/" + id + "/action";
    mvc.perform(get("/staff").with(user(staff.email()).roles("STAFF")))
        .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("/takeaway/orders/" + id)));
    mvc.perform(post(path).with(user(customer.email()).roles("CUSTOMER")).with(csrf()).param("action", "prepare"))
        .andExpect(status().isForbidden());
    mvc.perform(post(path).with(user(staff.email()).roles("STAFF")).param("action", "prepare"))
        .andExpect(status().isForbidden());
    assertThatThrownBy(() -> takeaway.staffAction(id, customer, "prepare", ""))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> takeaway.staffAction(id, staff, "complete", "")).hasMessageContaining("sẵn sàng");
    for (String action : List.of("prepare", "ready", "complete")) {
      mvc.perform(post(path).with(user(staff.email()).roles("STAFF")).with(csrf()).param("action", action))
          .andExpect(redirectedUrl("/takeaway/orders/" + id));
      mvc.perform(get("/takeaway/orders/" + id).with(user(staff.email()).roles("STAFF")))
          .andExpect(status().isOk());
    }
    var order = repo.takeaway(id).orElseThrow();
    assertThat(order.status()).isEqualTo(TakeawayStatus.COMPLETED);
    assertThat(order.paidAt()).isEqualTo(baseline);
    assertThat(order.paymentReference()).startsWith("CASH-");
    assertThatThrownBy(() -> takeaway.staffAction(id, staff, "complete", "")).hasMessageContaining("sẵn sàng");
    assertThatThrownBy(() -> takeaway.staffAction(id, staff, "cancel", "")).hasMessageContaining("Không thể hủy");
    assertThat(repo.takeaway(id).orElseThrow().paidAt()).isEqualTo(order.paidAt());
  }

  @Test
  void cashOrderCanBeCanceledWithoutRecordingPayment() {
    String id = takeaway.create(customer, takeawayInput("Khách hàng", "0901234567", baseline.plusMinutes(30), "CASH", Map.of(1L, 1)));
    takeaway.staffAction(id, staff, "cancel", "");
    assertThat(repo.takeaway(id).orElseThrow().status()).isEqualTo(TakeawayStatus.CANCELLED);
    assertThat(repo.takeaway(id).orElseThrow().paidAt()).isNull();
  }

  @Test
  void paginatedHistoryKeepsTotalsSearchAndCustomerIsolation() throws Exception {
    for (int i = 1; i <= 25; i++) create(baseline.plusDays(i).withHour(18));
    for (int i = 0; i < 21; i++)
      takeaway.create(customer, takeawayInput("Khách hàng", "0901234567", baseline.plusMinutes(30), "CASH", Map.of(1L, 1)));
    var first = repo.bookingPage(customer.id(), 0, "");
    var second = repo.bookingPage(customer.id(), 1, "");
    assertThat(first.items()).hasSize(20);
    assertThat(second.items()).hasSize(5);
    assertThat(first.total()).isEqualTo(25);
    assertThat(first.items()).doesNotContainAnyElementsOf(second.items());
    assertThat(repo.bookingPage(staff.id(), 0, "").total()).isZero();
    assertThat(repo.bookingPage(customer.id(), -1, "").number()).isZero();
    assertThat(repo.bookingPage(customer.id(), Integer.MAX_VALUE, "").number()).isEqualTo(1);
    assertThat(repo.takeawayPage(customer.id(), 0).items()).hasSize(20);
    assertThat(repo.takeawayPage(customer.id(), 1).items()).hasSize(1);
    assertThat(repo.takeawayPage(staff.id(), 0).total()).isZero();
    String search = second.items().getFirst().code();
    assertThat(repo.bookingPage(null, 0, search).items()).singleElement();
    mvc.perform(get("/bookings").param("page", "1").param("takeawayPage", "1").with(user(customer.email()).roles("CUSTOMER")))
        .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Trang 2 / 2")));
    mvc.perform(get("/staff").param("search", search).with(user(staff.email()).roles("STAFF")))
        .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(search)));
  }

  @Test
  void repositoryLoadsListsInBatchesAndAvailabilityDoesNotLoadFood() {
    for (int i = 1; i <= 25; i++) create(baseline.plusDays(i).withHour(18));
    var statements = new ArrayList<String>();
    var source = new org.springframework.jdbc.datasource.DelegatingDataSource(repo.jdbc().getDataSource()) {
      @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
        var connection = super.getConnection();
        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
              if (method.getName().equals("prepareStatement")) statements.add((String) args[0]);
              try { return method.invoke(connection, args); }
              catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
            });
      }
    };
    var counted = new RestaurantRepository(new org.springframework.jdbc.core.JdbcTemplate(source));
    assertThat(counted.bookingPage(customer.id(), 0, "").items()).hasSize(20)
        .allSatisfy(b -> { assertThat(b.tableIds()).containsExactly(1L); assertThat(b.lines()).hasSize(1); });
    assertThat(statements).hasSize(4); // COUNT + bookings + tables + food, independent of page size.
    statements.clear();
    var start = baseline.plusDays(1).withHour(18);
    assertThat(counted.busyTableIds(start, start.plusHours(2), baseline, 15, null)).contains(1L);
    assertThat(statements).singleElement().satisfies(sql -> assertThat(sql).doesNotContain("order_line", "app_user"));
  }

  @Test
  void takeawayHistoryIsNewestFirstAndRestrictedToTheSignedInCustomer() throws Exception {
    String own = takeaway.create(customer,
        takeawayInput("Khách trải nghiệm", "0901234567", baseline.plusHours(1), "CASH", Map.of(1L, 1)));
    clock.set(baseline.plusMinutes(1));
    String newer = takeaway.create(customer,
        takeawayInput("Khách trải nghiệm", "0901234567", baseline.plusHours(1), "CASH", Map.of(2L, 2)));
    if (repo.account("history.other@moc.local").isEmpty())
      service.register("history.other@moc.local", "Password123!", "Khách khác", "0911111111");
    Account otherCustomer = service.account("history.other@moc.local");
    String other = takeaway.create(otherCustomer,
        takeawayInput("Người nhận khác", "0911111111", baseline.plusHours(1), "CASH", Map.of(1L, 1)));
    assertThat(repo.takeawayOrders(customer.id())).extracting(TakeawayOrder::id).containsExactly(newer, own);
    String page = mvc.perform(get("/bookings").param("userId", String.valueOf(staff.id()))
            .with(user(customer.email()).roles("CUSTOMER")))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(page).contains(own, newer).doesNotContain(other, "Người nhận khác");
    mvc.perform(get("/takeaway/orders/" + other).with(user(customer.email()).roles("CUSTOMER")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/bookings")).andExpect(status().is3xxRedirection());
  }

  @Test
  void historyHasAnEmptyStateWhenNeitherBookingsNorTakeawayOrdersExist() throws Exception {
    mvc.perform(get("/bookings").with(user(customer.email()).roles("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Bạn chưa có lượt đặt bàn hay đơn đến lấy.")));
  }

  @Test
  void employeesHaveOperationsInsteadOfPersonalBookingAndCannotCreateCustomerOrders() throws Exception {
    String id = create(baseline.plusDays(1).withHour(18));
    for (Account employee : List.of(staff, admin)) {
      for (String path : List.of("/", "/menu", "/staff")) {
        String html = mvc.perform(get(path).with(user(employee.email()).roles(employee.role())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("href=\"/book\"", "href=\"/bookings\"", "Lịch hẹn của tôi");
      }
      for (String path : List.of("/book", "/bookings", "/takeaway"))
        mvc.perform(get(path).with(user(employee.email()).roles(employee.role()))).andExpect(status().isForbidden());
      for (String path : List.of("/api/bookings", "/api/takeaway-orders"))
        mvc.perform(post(path).with(user(employee.email()).roles(employee.role())).with(csrf())
            .contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
      mvc.perform(get("/bookings/" + id).with(user(employee.email()).roles(employee.role())))
          .andExpect(status().isOk());
      assertThatThrownBy(() -> service.create(employee, input(baseline.plusDays(2).withHour(18), 2, List.of(1L))))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      assertThatThrownBy(() -> takeaway.create(employee, takeawayInput("Nhân viên", "0901234567", baseline.plusMinutes(30), "CASH", Map.of(1L, 1))))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    mvc.perform(post("/login").with(csrf()).param("email", staff.email()).param("password", "MocDemo123!"))
        .andExpect(redirectedUrl("/staff"));
  }

  @Test
  void promotionIsOffByDefaultAndSnapshotsEligibleFoodDiscountWithoutAddingDepositToTotal() throws Exception {
    assertThat(repo.promotion().enabled()).isFalse();
    service.updatePromotion(admin, true, 4, 10);
    String smaller = service.create(customer, new BookingService.BookingInput(baseline.plusDays(1).withHour(18),
        baseline.plusDays(1).withHour(20), 3, List.of(1L), Map.of(1L, 2), "", 0));
    String id = service.create(customer, new BookingService.BookingInput(baseline.plusDays(2).withHour(18),
        baseline.plusDays(2).withHour(20), 4, List.of(1L), Map.of(1L, 2), "", 10));
    var booking = repo.booking(id).orElseThrow();
    assertThat(repo.booking(smaller).orElseThrow().discount()).isZero();
    assertThat(booking.discount()).isEqualTo(booking.foodTotal() / 10);
    assertThat(booking.total()).isEqualTo(booking.foodTotal() - booking.discount());
    assertThat(booking.deposit()).isEqualTo(100000 + BookingPolicy.foodDeposit(booking.total()));
    service.updatePromotion(admin, false, 3, 20);
    assertThat(repo.booking(id).orElseThrow().total()).isEqualTo(booking.total());
    service.editItems(id, customer, Map.of(1L, 3), "");
    var edited = repo.booking(id).orElseThrow();
    assertThat(edited.appliedDiscountPercent()).isEqualTo(10);
    assertThat(edited.foodDeposit()).isEqualTo(BookingPolicy.foodDeposit(edited.total()));
    mvc.perform(get("/staff").with(user(staff.email()).roles("STAFF")))
        .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Tổng tiền")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Đã giảm")));
    mvc.perform(get("/bookings/" + id).with(user(customer.email()).roles("CUSTOMER")))
        .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Khuyến mãi nhóm (10%)")));
    assertThat(BookingPolicy.discount(101, 10)).isEqualTo(10);
  }

  @Test
  void changedPromotionRequiresRefreshingAndOnlyAdminCanConfigureIt() throws Exception {
    service.updatePromotion(admin, true, 4, 10);
    assertThatThrownBy(() -> service.create(customer, input(baseline.plusDays(1).withHour(18), 4, List.of(1L))))
        .hasMessageContaining("Khuyến mãi vừa thay đổi");
    assertThat(repo.bookings(customer.id())).isEmpty();
    assertThatThrownBy(() -> service.updatePromotion(staff, true, 4, 10))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> service.updatePromotion(admin, true, 1, 10)).hasMessageContaining("2 đến 12");
    assertThatThrownBy(() -> service.updatePromotion(admin, true, 4, 51)).hasMessageContaining("50%");
    mvc.perform(post("/admin/promotion").with(user(staff.email()).roles("STAFF")).with(csrf())
        .param("enabled", "true").param("minimumGuests", "3").param("percent", "5"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/admin/promotion").with(user(admin.email()).roles("ADMIN")).with(csrf())
        .param("enabled", "true").param("minimumGuests", "3").param("percent", "5"))
        .andExpect(redirectedUrl("/admin"));
    mvc.perform(get("/admin").with(user(admin.email()).roles("ADMIN"))).andExpect(status().isOk());
  }

  @Test
  void staffCanViewStockAndWarningsButOnlyAdminSeesEditingControls() throws Exception {
    assertThat(repo.stockItems().getFirst().quantity()).isNull();
    service.updateStock(admin, 1, 3, 5, true);
    assertThat(repo.stockItems().getFirst().low()).isTrue();
    String html = mvc.perform(get("/menu").with(user(staff.email()).roles("STAFF")))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(html).contains("Sắp hết", "Số phần tồn", "Ngưỡng: 5 phần", "Nhân viên chỉ xem")
        .doesNotContain("stock-form", "Cập nhật tồn &amp; phục vụ", "name=\"quantity\"", "name=\"threshold\"",
            "name=\"available\"", "/admin/menu/", "/staff/menu/", "order-choice", "Chốt đơn");
    String adminMenu = mvc.perform(get("/menu").with(user(admin.email()).roles("ADMIN")))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(adminMenu).contains("Sắp hết", "/admin/menu/1/stock", "name=\"quantity\"",
        "name=\"threshold\"", "name=\"available\"", "Lưu");
    String customerMenu = mvc.perform(get("/menu").with(user(customer.email()).roles("CUSTOMER")))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(customerMenu).doesNotContain("Số phần tồn", "stock-form", "/admin/menu/");
  }

  @Test
  void staffAndCustomersCannotChangeStockThroughHttpOrService() throws Exception {
    service.updateStock(admin, 1, 3, 5, true);
    var before = repo.stockItems().getFirst();
    for (Account actor : List.of(staff, customer)) {
      mvc.perform(post("/admin/menu/1/stock").with(user(actor.email()).roles(actor.role())).with(csrf())
          .param("quantity", "0").param("threshold", "10").param("available", "false"))
          .andExpect(status().isForbidden());
      mvc.perform(post("/staff/menu/1/stock").with(user(actor.email()).roles(actor.role())).with(csrf())
          .param("quantity", "0").param("threshold", "10").param("available", "false"))
          .andExpect(status().is4xxClientError());
      assertThatThrownBy(() -> service.updateStock(actor, 1, 0, 10, false))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      assertThat(repo.stockItems().getFirst()).isEqualTo(before);
    }
  }

  @Test
  void adminCanUpdateStockThresholdAndAvailabilityAndZeroStockBlocksNewOrders() throws Exception {
    mvc.perform(post("/admin/menu/1/stock").with(user(admin.email()).roles("ADMIN")).with(csrf())
        .param("quantity", "0").param("threshold", "2").param("available", "true"))
        .andExpect(redirectedUrl("/menu"));
    assertThat(repo.stockItems().getFirst().empty()).isTrue();
    assertThat(repo.stockItems().getFirst().threshold()).isEqualTo(2);
    assertThat(repo.dishes().getFirst().available()).isFalse();
    assertThatThrownBy(() -> takeaway.quote(Map.of(1L, 1))).hasMessageContaining("tạm hết");
    assertThatThrownBy(() -> create(baseline.plusDays(1).withHour(18))).hasMessageContaining("tạm hết");
    assertThatThrownBy(() -> service.updateStock(admin, 1, -1, 5, true)).hasMessageContaining("Số phần tồn");
    assertThatThrownBy(() -> service.updateStock(admin, 1, 8, -1, true)).hasMessageContaining("Ngưỡng cảnh báo");
    mvc.perform(post("/admin/menu/1/stock").with(user(admin.email()).roles("ADMIN")).with(csrf())
        .param("quantity", "8").param("threshold", "10"))
        .andExpect(redirectedUrl("/menu"));
    assertThat(repo.stockItems().getFirst().quantity()).isEqualTo(8);
    assertThat(repo.stockItems().getFirst().threshold()).isEqualTo(10);
    assertThat(repo.stockItems().getFirst().low()).isTrue();
    assertThat(repo.dishes().getFirst().available()).isFalse();
    service.updateStock(admin, 1, 8, 5, true);
    assertThat(repo.dishes().getFirst().available()).isTrue();
    create(baseline.plusDays(1).withHour(18));
    assertThat(repo.stockItems().getFirst().quantity()).isEqualTo(8); // Manual inventory, as described in the UI.
  }

  @Test
  void gardenLayoutKeepsEntranceClearAndVerticalBookingsBlockEveryTable() {
    assertThat(repo.tables().stream().filter(t -> t.floor() == 1).toList())
        .extracting(DiningTable::code)
        .containsExactly("B01", "B02", "B03", "B04", "B05", "B06", "B07", "B08", "B09");
    assertThat(repo.tables()).noneMatch(t -> t.mapX() == 1 && (t.mapY() == 1 || t.mapY() == 2));
    var start = baseline.plusHours(4);
    var pair =
        service.availability(start, start.plusHours(2), 8).options().stream()
            .filter(o -> o.label().equals("B01 + B04"))
            .findFirst()
            .orElseThrow();
    assertThat(pair.deposit()).isEqualTo(200000);
    service.create(customer, input(start, 8, pair.tableIds()));
    assertThat(service.availability(start, start.plusHours(2), 2).unavailable()).contains(1L, 4L);
    assertThat(service.availability(start, start.plusHours(2), 8).options())
        .noneMatch(o -> o.tableIds().contains(1L) || o.tableIds().contains(4L));
    var triple = service.resolveTableNumbers("3,5,7");
    String id = service.create(customer, input(start, 9, triple));
    assertThat(repo.booking(id).orElseThrow().tablesLabel()).isEqualTo("B03 + B05 + B07");
    assertThat(repo.booking(id).orElseThrow().tableDeposit()).isEqualTo(300000);
    assertThatThrownBy(() -> service.create(customer, input(start, 2, List.of(5L))))
        .hasMessageContaining("không còn phục vụ");
  }

  @Test
  void combinationsCannotCrossGardenOrSkipTablesAndUseVisibleNumbers() {
    assertThatThrownBy(() -> service.saveCombination(admin, "4,5", false))
        .hasMessageContaining("khu vườn");
    assertThatThrownBy(() -> service.saveCombination(admin, "1,6", false))
        .hasMessageContaining("liền nhau");
    assertThatThrownBy(() -> service.saveCombination(admin, "1,5", false))
        .hasMessageContaining("liền nhau");
    service.saveCombination(admin, "5,7", true);
    assertThat(repo.combinations()).doesNotContain(List.of(6L, 9L));
    service.saveCombination(admin, "5,7", false);
    assertThat(repo.combinations()).contains(List.of(6L, 9L));
  }

  @Test
  void layoutUpgradePreservesExistingReservationsAndRunsOnlyOnce() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              repo.jdbc().update("DELETE FROM app_migration WHERE name='garden-layout-v1'");
              repo.jdbc().update("DELETE FROM app_migration WHERE name='upper-floor-v1'");
              repo.jdbc().update("DELETE FROM app_migration WHERE name='entrance-clearance-v1'");
              repo.jdbc().update("DELETE FROM app_migration WHERE name='ground-numbering-v2'");
              repo.jdbc().update("DELETE FROM table_combination");
              repo.jdbc().update("DELETE FROM dining_table WHERE floor=2");
              repo.jdbc().update("UPDATE dining_table SET code='TMP-' || id WHERE floor=1");
              for (int id = 1; id <= 12; id++) {
                repo.jdbc()
                    .update(
                        "UPDATE dining_table SET code=?,retired=FALSE,active=TRUE WHERE id=?",
                        "B%02d".formatted(id),
                        id);
              }
              String existing =
                  service.create(customer, input(baseline.plusHours(4), 2, List.of(5L)));
              seed.run();
              assertThat(repo.booking(existing).orElseThrow().tableIds()).containsExactly(5L);
              assertThat(repo.booking(existing).orElseThrow().tablesLabel()).isEqualTo("OLD-B05");
              assertThat(repo.tables()).hasSize(19);
              assertThat(repo.combinations()).hasSize(29);
              service.saveCombination(admin, "1,4", true);
              seed.run();
              assertThat(repo.combinations()).doesNotContain(List.of(1L, 4L));
              assertThat(repo.tables()).extracting(DiningTable::code).contains("B05", "B09");
              status.setRollbackOnly();
            });
  }

  @Test
  void upperFloorCanBeBookedWithoutBlockingGroundFloor() {
    var upper = repo.tables().stream().filter(t -> t.floor() == 2).toList();
    assertThat(upper)
        .extracting(DiningTable::code)
        .containsExactly("B11", "B12", "B13", "B14", "B15", "B16", "B17", "B18", "B19", "B20");
    var start = baseline.plusHours(4);
    var ids = service.resolveTableNumbers("11,14");
    String booking = service.create(customer, input(start, 8, ids));
    assertThat(repo.booking(booking).orElseThrow().tablesLabel()).isEqualTo("B11 + B14");
    assertThat(repo.booking(booking).orElseThrow().tableDeposit()).isEqualTo(200000);
    var availability = service.availability(start, start.plusHours(2), 8);
    assertThat(availability.unavailable()).containsAll(ids).doesNotContain(1L, 4L);
    assertThat(availability.options()).anyMatch(o -> o.label().equals("B01 + B04"));
    assertThat(availability.options()).noneMatch(o -> o.label().equals("B11 + B14"));
  }

  @Test
  void crossFloorCombinationsAreRejectedEvenIfStoredInDatabase() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              assertThatThrownBy(() -> service.saveCombination(admin, "1,14", false))
                  .hasMessageContaining("cùng tầng");
              var ids = service.resolveTableNumbers("1,14");
              String raw = String.join(",", ids.stream().map(String::valueOf).toList());
              repo.jdbc().update("INSERT INTO table_combination(table_ids) VALUES(?)", raw);
              var start = baseline.plusHours(4);
              assertThat(service.availability(start, start.plusHours(2), 8).options())
                  .noneMatch(o -> o.tableIds().equals(ids));
              assertThatThrownBy(() -> service.create(customer, input(start, 8, ids)))
                  .hasMessageContaining("tổ hợp");
              status.setRollbackOnly();
            });
  }

  @Test
  void restartingPreservesUpperFloorSettingsAndExistingBooking() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              String existing = create(baseline.plusHours(4));
              var oldTables = repo.booking(existing).orElseThrow().tableIds();
              service.saveCombination(admin, "11,14", true);
              service.toggleTable(admin, service.resolveTableNumbers("20").getFirst());
              seed.run();
              assertThat(repo.tables()).hasSize(19);
              assertThat(repo.booking(existing).orElseThrow().tableIds()).isEqualTo(oldTables);
              assertThat(repo.combinations()).doesNotContain(service.resolveTableNumbers("11,14"));
              assertThat(
                      repo.tables().stream()
                          .filter(t -> t.code().equals("B20"))
                          .findFirst()
                          .orElseThrow()
                          .active())
                  .isFalse();
              status.setRollbackOnly();
            });
  }

  @Test
  void entranceMigrationAndRenumberingPreserveHistoryAndKeepEntranceClear() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              repo.jdbc().update("DELETE FROM app_migration WHERE name='ground-numbering-v2'");
              repo.jdbc().update("UPDATE dining_table SET code='B10' WHERE code='B09' AND retired=FALSE AND floor=1");
              repo.jdbc().update("UPDATE dining_table SET code='B09' WHERE code='OLD-B09' AND floor=1");
              long retiredId =
                  repo.jdbc()
                      .queryForObject(
                          "SELECT id FROM dining_table WHERE code='B09' AND floor=1", Long.class);
              repo.jdbc()
                  .update(
                      "UPDATE dining_table SET active=TRUE,retired=FALSE WHERE id=?", retiredId);
              repo.jdbc().update("DELETE FROM app_migration WHERE name='entrance-clearance-v1'");
              service.saveCombination(admin, "8,9", false);
              service.saveCombination(admin, "9,10", false);
              service.saveCombination(admin, "8,9,10", false);
              var start = baseline.plusHours(4);
              String existing = service.create(customer, input(start, 2, List.of(retiredId)));
              repo.jdbc().update("UPDATE reservation SET deposit=100000,food_deposit=0 WHERE id=?", existing);
              String corner = service.create(customer, input(start, 2, service.resolveTableNumbers("10")));
              var cornerIds = repo.booking(corner).orElseThrow().tableIds();
              simulateVerifiedPayment(existing);
              seed.run();
              seed.run();
              assertThat(repo.tables()).hasSize(19).noneMatch(t -> t.id() == retiredId);
              assertThat(repo.tables()).anyMatch(t -> t.code().equals("B09") && t.mapX() == 2 && t.mapY() == 3);
              assertThat(repo.tables()).noneMatch(t -> t.floor() == 1 && t.mapX() == 1 && t.mapY() == 3);
              assertThat(repo.booking(corner).orElseThrow().tableIds()).isEqualTo(cornerIds);
              assertThat(repo.booking(corner).orElseThrow().tablesLabel()).isEqualTo("B09");
              assertThat(repo.tables())
                  .anyMatch(t -> t.code().equals("B19") && t.floor() == 2 && t.active());
              assertThat(repo.combinations()).noneMatch(ids -> ids.contains(retiredId));
              assertThat(repo.combinations()).contains(service.resolveTableNumbers("18,19,20"));
              var booking = repo.booking(existing).orElseThrow();
              assertThat(booking.tablesLabel()).isEqualTo("OLD-B09");
              assertThat(booking.paymentStatus()).isEqualTo(PaymentStatus.PAID);
              assertThat(booking.deposit()).isEqualTo(100000);
              assertThat(
                      new vn.edu.giavien.web.ViewSupport(repo).hasRetiredTables(booking.tableIds()))
                  .isTrue();
              assertThat(service.availability(start, start.plusHours(2), 2).options())
                  .noneMatch(o -> o.tableIds().contains(retiredId));
              assertThatThrownBy(
                      () -> service.create(customer, input(start, 2, List.of(retiredId))))
                  .hasMessageContaining("không còn phục vụ");
              assertThatThrownBy(() -> service.saveCombination(admin, "8,9", false))
                  .hasMessageContaining("liền nhau");
              status.setRollbackOnly();
            });
  }

  @Test
  void refundBoundaryIsExactlyThreeHoursAndNoFreshPaymentException() {
    assertThat(BookingPolicy.refundable(baseline, baseline.plusHours(3))).isTrue();
    assertThat(BookingPolicy.refundable(baseline.plusSeconds(1), baseline.plusHours(3))).isFalse();
    String refundable = paid(baseline.plusHours(3));
    service.cancel(refundable, customer, false);
    assertThat(repo.booking(refundable).orElseThrow().paymentStatus())
        .isEqualTo(PaymentStatus.REFUND_PENDING);
    String late = paid(baseline.plusHours(2));
    service.cancel(late, customer, false);
    assertThat(repo.booking(late).orElseThrow().paymentStatus()).isEqualTo(PaymentStatus.FORFEITED);
  }

  @Test
  void restaurantCancellationAlwaysRefundsAndRefundRequiresReference() {
    String id = paid(baseline.plusMinutes(30));
    service.cancel(id, staff, true);
    assertThat(repo.booking(id).orElseThrow().paymentStatus())
        .isEqualTo(PaymentStatus.REFUND_PENDING);
    assertThatThrownBy(() -> service.staffAction(id, staff, "refund", ""))
        .isInstanceOf(IllegalArgumentException.class);
    service.staffAction(id, staff, "refund", "BANK-TEST-123");
    assertThat(repo.booking(id).orElseThrow().paymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
  }

  @Test
  void validThreeTableCombinationSupportsNineGuestsAndSnapshotsDeposit() {
    String id = service.create(customer, input(baseline.plusHours(5), 9, List.of(3L, 1L, 2L)));
    assertThat(repo.booking(id).orElseThrow().tableDeposit()).isEqualTo(300000);
    service.updateSettings(admin, 200000, 15, 15, 15);
    assertThat(repo.booking(id).orElseThrow().tableDeposit()).isEqualTo(300000);
    assertThatThrownBy(
            () -> service.create(customer, input(baseline.plusHours(5), 9, List.of(4L, 5L))))
        .hasMessageContaining("Số bàn");
    assertThatThrownBy(
            () -> service.create(customer, input(baseline.plusHours(5), 8, List.of(4L, 9L))))
        .hasMessageContaining("tổ hợp");
  }

  @Test
  void unresolvedBookingHoldsTheWholeServiceDateAndExpiresWithoutPayment() {
    String id = create(baseline.plusHours(2));
    assertThatThrownBy(() -> create(baseline.plusHours(4).plusMinutes(14)))
        .hasMessageContaining("Bàn vừa");
    assertThatThrownBy(() -> create(baseline.plusHours(4).plusMinutes(15)))
        .hasMessageContaining("Bàn vừa");
    assertThat(create(baseline.plusDays(1).plusHours(2))).isNotBlank();
    clock.set(baseline.plusMinutes(15));
    service.expireHolds();
    assertThat(repo.booking(id).orElseThrow().status()).isEqualTo(BookingStatus.EXPIRED);
    assertThat(create(baseline.plusHours(2))).isNotBlank();
  }

  @Test
  void pendingGroupBlocksEveryIndividualTable() {
    service.create(customer, input(baseline.plusHours(4), 9, List.of(1L, 2L, 3L)));
    var availability = service.availability(baseline.plusHours(4), baseline.plusHours(5), 2);
    assertThat(availability.unavailable()).contains(1L, 2L, 3L);
  }

  @Test
  void editingStopsAfterTwoHourBoundaryAndPreservesStoredPrices() {
    String id = paid(baseline.plusHours(3));
    clock.set(baseline.plusHours(1));
    long oldPrice = repo.booking(id).orElseThrow().lines().getFirst().unitPrice();
    repo.jdbc().update("UPDATE menu_item SET price=price+1000 WHERE id=1");
    service.editItems(id, customer, Map.of(1L, 3), "Ít cay");
    assertThat(repo.booking(id).orElseThrow().lines().getFirst().unitPrice()).isEqualTo(oldPrice);
    clock.set(baseline.plusHours(1).plusSeconds(1));
    assertThatThrownBy(() -> service.editItems(id, customer, Map.of(), ""))
        .hasMessageContaining("2 tiếng");
  }

  @Test
  void menuAvailabilityAndQuantityAreEnforced() {
    repo.jdbc().update("UPDATE menu_item SET available=FALSE WHERE id=1");
    assertThatThrownBy(() -> create(baseline.plusHours(4))).hasMessageContaining("tạm hết");
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new BookingService.BookingInput(
                        baseline.plusHours(4),
                        baseline.plusHours(5),
                        2,
                        List.of(1L),
                        Map.of(2L, -1),
                        "")))
        .hasMessageContaining("Số lượng");
  }

  @Test
  void onlyOneConcurrentCustomerCanHoldTheSameTables() throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      Callable<Boolean> attempt =
          () -> {
            start.await();
            try {
              create(baseline.plusHours(4));
              return true;
            } catch (IllegalArgumentException e) {
              return false;
            }
          };
      var first = pool.submit(attempt);
      var second = pool.submit(attempt);
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void lateGatewaySuccessDoesNotReclaimReleasedTableAndIsIdempotent() {
    String id = create(baseline.plusHours(3));
    clock.set(baseline.plusMinutes(16));
    service.expireHolds();
    String replacement = create(baseline.plusHours(3));
    long amount = repo.booking(id).orElseThrow().deposit();
    assertThat(service.gatewayPayment(id, amount, "12345", true)).isEqualTo("00");
    Booking late = repo.booking(id).orElseThrow();
    assertThat(late.status()).isEqualTo(BookingStatus.EXPIRED);
    assertThat(late.paymentStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
    assertThat(service.gatewayPayment(id, amount, "12345", true)).isEqualTo("02");
    assertThat(repo.booking(replacement).orElseThrow().status()).isEqualTo(BookingStatus.PENDING);
  }

  @Test
  void wrongGatewayAmountCannotConfirmBooking() {
    String id = create(baseline.plusHours(3));
    assertThat(service.gatewayPayment(id, 1, "123", true)).isEqualTo("04");
    assertThat(repo.booking(id).orElseThrow().paymentStatus()).isEqualTo(PaymentStatus.UNPAID);
  }

  @Test
  void completedTableStillNeedsCleanupBeforeReuse() {
    String id = paid(baseline.plusHours(1));
    clock.set(baseline.plusHours(1));
    service.staffAction(id, staff, "checkin", "");
    clock.set(baseline.plusHours(2));
    service.staffAction(id, staff, "complete", "");
    assertThatThrownBy(() -> create(baseline.plusHours(2).plusMinutes(14)))
        .hasMessageContaining("Bàn vừa");
    assertThat(create(baseline.plusHours(2).plusMinutes(15))).isNotBlank();
  }

  @Test
  void onlyAdminCanCreateStaffAndCannotPromoteExistingCustomerByReusingEmail() {
    assertThatThrownBy(
            () ->
                service.createStaff(
                    customer, "newstaff@moc.local", "StaffPass123!", "Nhân viên", "0901234567"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    service.createStaff(admin, "newstaff@moc.local", "StaffPass123!", "Nhân viên", "0901234567");
    assertThat(repo.account("newstaff@moc.local").orElseThrow().role()).isEqualTo("STAFF");
    assertThatThrownBy(
            () ->
                service.createStaff(
                    admin, customer.email(), "StaffPass123!", "Nhân viên", "0901234567"))
        .hasMessageContaining("đã được đăng ký");
    assertThat(repo.account(customer.email()).orElseThrow().role()).isEqualTo("CUSTOMER");
  }

  @Test
  void changeRequestKeepsOriginalUntilStaffAppliesValidatedSchedule() {
    String id = paid(baseline.plusHours(4));
    service.requestChange(id, customer, "Xin đổi sang 15 giờ cùng ngày");
    assertThat(repo.booking(id).orElseThrow().startAt()).isEqualTo(baseline.plusHours(4));
    service.reschedule(
        id,
        staff,
        baseline.plusHours(5),
        baseline.plusHours(7),
        2,
        List.of(2L),
        "Đã đổi sang 15:00");
    assertThat(repo.booking(id).orElseThrow().tableIds()).containsExactly(2L);
    assertThat(repo.requests(id).getFirst().status()).isEqualTo("APPROVED");
  }

  @Test
  void kitchenOnlyStartsAfterCheckinAndLifecycleCannotBeSkipped() {
    String id = paid(baseline.plusHours(2));
    assertThatThrownBy(() -> service.staffAction(id, staff, "complete", ""))
        .hasMessageContaining("nhận bàn");
    assertThatThrownBy(() -> service.staffAction(id, staff, "checkin", ""))
        .hasMessageContaining("15 phút");
    clock.set(baseline.plusHours(2));
    service.staffAction(id, staff, "checkin", "");
    assertThat(repo.booking(id).orElseThrow().status()).isEqualTo(BookingStatus.SEATED);
    service.staffAction(id, staff, "complete", "");
    assertThat(repo.booking(id).orElseThrow().status()).isEqualTo(BookingStatus.COMPLETED);
  }

  @Test
  void anonymousAndCrossRoleAccessAreBlockedAndCsrfRequired() throws Exception {
    mvc.perform(get("/book")).andExpect(status().is3xxRedirection());
    mvc.perform(get("/api/availability")).andExpect(status().isUnauthorized());
    mvc.perform(get("/admin").with(user(customer.email()).roles("CUSTOMER")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/bookings")
                .with(user(customer.email()).roles("CUSTOMER"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    String id = paid(baseline.plusHours(4));
    service.register("another@example.com", "OtherPass123!", "Khách khác", "0901234567");
    mvc.perform(get("/bookings/" + id).with(user("another@example.com").roles("CUSTOMER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void templatesRenderForPublicCustomerAndStaff() throws Exception {
    for (String url : List.of("/", "/menu", "/login", "/register"))
      mvc.perform(get(url)).andExpect(status().isOk());
    for (String url : List.of("/book", "/bookings"))
      mvc.perform(get(url).with(user(customer.email()).roles("CUSTOMER")))
          .andExpect(status().isOk());
    String id = paid(baseline.plusHours(4));
    service.requestChange(id, customer, "Đổi giờ đến giúp mình");
    mvc.perform(get("/bookings/" + id).with(user(customer.email()).roles("CUSTOMER")))
        .andExpect(status().isOk());
    mvc.perform(get("/bookings/" + id).with(user(staff.email()).roles("STAFF")))
        .andExpect(status().isOk());
    mvc.perform(get("/staff").with(user(staff.email()).roles("STAFF"))).andExpect(status().isOk());
    mvc.perform(get("/admin").with(user(admin.email()).roles("ADMIN"))).andExpect(status().isOk());
    mvc.perform(get("/payment/vnpay/return")).andExpect(status().isOk());
  }

  @Test
  void gardenMenuMigrationAddsFourDishesOnceAndPreservesExistingEdits() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            transaction -> {
              repo.lockSchedule();
              repo.jdbc().update("DELETE FROM app_migration WHERE name='garden-menu-v1'");
              repo.jdbc()
                  .update(
                      "DELETE FROM menu_item WHERE illustration IN"
                          + " ('beef','prawns','mushrooms','flan')");
              repo.jdbc().update("UPDATE menu_item SET price=77000,available=FALSE WHERE id=1");
              int before = repo.dishes().size();
              seed.run();
              seed.run();
              assertThat(repo.dishes()).hasSize(before + 4);
              assertThat(repo.dishes())
                  .extracting(Dish::name)
                  .contains("Bò lúc lắc", "Tôm nướng muối ớt", "Nấm kho tiêu", "Bánh flan caramel");
              var original =
                  repo.dishes().stream().filter(d -> d.id() == 1).findFirst().orElseThrow();
              assertThat(original.price()).isEqualTo(77000);
              assertThat(original.available()).isFalse();
              transaction.setRollbackOnly();
            });
  }

  @Test
  void menuPhotosAndNewDishOrderingIncludeFoodInTotalDeposit() throws Exception {
    var fmt = new vn.edu.giavien.web.ViewSupport(repo);
    for (var dish : repo.dishes()) {
      String path = fmt.dishImage(dish);
      mvc.perform(get(path))
          .andExpect(status().isOk())
          .andExpect(content().contentType("image/jpeg"));
    }
    var beef =
        repo.dishes().stream()
            .filter(d -> d.illustration().equals("beef"))
            .findFirst()
            .orElseThrow();
    String id =
        service.create(
            customer,
            new BookingService.BookingInput(
                baseline.plusDays(1).withHour(18),
                baseline.plusDays(1).withHour(20),
                2,
                List.of(1L),
                Map.of(beef.id(), 2),
                ""));
    var booking = repo.booking(id).orElseThrow();
    assertThat(booking.foodTotal()).isEqualTo(beef.price() * 2);
    assertThat(booking.foodDeposit()).isEqualTo(beef.price() * 2 / 5);
    assertThat(booking.tableDeposit()).isEqualTo(repo.settings().depositPerTable());
    assertThat(booking.deposit()).isEqualTo(repo.settings().depositPerTable() + beef.price() * 2 / 5);
    mvc.perform(get("/book").with(user(customer.email()).roles("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("/images/menu/bo-luc-lac.jpg")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"dish-search\"")));
  }

  @Test
  void itemEditsUpdateUnpaidDepositButKeepPaidAmountAndTableRateSnapshot() throws Exception {
    String id = create(baseline.plusDays(1).withHour(18));
    long price = repo.booking(id).orElseThrow().lines().getFirst().unitPrice();
    service.updateSettings(admin, 200000, 15, 15, 15);
    service.editItems(id, customer, Map.of(1L, 3), "Ít cay");
    Booking beforePayment = repo.booking(id).orElseThrow();
    assertThat(beforePayment.tableDeposit()).isEqualTo(100000);
    assertThat(beforePayment.foodDeposit()).isEqualTo(price * 3 / 5);
    assertThat(beforePayment.deposit()).isEqualTo(100000 + price * 3 / 5);
    mvc.perform(get("/bookings/" + id).with(user(customer.email()).roles("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Tiền món trả trước")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Chưa thanh toán")));
    simulateVerifiedPayment(id);
    service.editItems(id, customer, Map.of(1L, 1), "Đổi số phần");
    Booking afterPayment = repo.booking(id).orElseThrow();
    assertThat(afterPayment.foodTotal()).isEqualTo(price);
    assertThat(afterPayment.foodDeposit()).isEqualTo(beforePayment.foodDeposit());
    assertThat(afterPayment.deposit()).isEqualTo(beforePayment.deposit());
    service.cancel(id, customer, false);
    assertThat(repo.booking(id).orElseThrow().paymentStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
    assertThat(repo.booking(id).orElseThrow().deposit()).isEqualTo(beforePayment.deposit());
  }

  @Test
  void twentyPercentFoodDepositRoundsOnceOnCombinedTotalAndVnpayUsesThatAmount() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              repo.jdbc().update("UPDATE menu_item SET price=10001 WHERE id=1");
              repo.jdbc().update("UPDATE menu_item SET price=10002 WHERE id=2");
              String id = service.create(customer, new BookingService.BookingInput(
                  baseline.plusHours(4), baseline.plusHours(6), 2, List.of(1L),
                  Map.of(1L, 1, 2L, 1), ""));
              Booking booking = repo.booking(id).orElseThrow();
              assertThat(booking.foodTotal()).isEqualTo(20003);
              assertThat(booking.foodDeposit()).isEqualTo(4001);
              assertThat(booking.deposit()).isEqualTo(104001);
              service.editItems(id, customer, Map.of(1L, 1), "");
              assertThat(repo.booking(id).orElseThrow().foodDeposit()).isEqualTo(2001);
              assertThat(repo.booking(id).orElseThrow().deposit()).isEqualTo(102001);
              var parameters = urlParameters(payments.start(id, customer, "127.0.0.1"));
              assertThat(parameters.get("vnp_Amount")).isEqualTo("10200100");
              assertThat(gateway.verified(parameters)).isTrue();
              status.setRollbackOnly();
            });
  }

  @Test
  void noPreorderedFoodRequiresOnlyTableDepositAndVnpayAmountsCannotChangeAfterCheckout() throws Exception {
    String id = service.create(customer, new BookingService.BookingInput(
        baseline.plusHours(4), baseline.plusHours(6), 2, List.of(1L), Map.of(), ""));
    assertThat(repo.booking(id).orElseThrow().deposit()).isEqualTo(100000);
    assertThat(repo.booking(id).orElseThrow().foodDeposit()).isZero();
    String reference = startPayment(id);
    assertThatThrownBy(() -> service.editItems(id, customer, Map.of(1L, 1), ""))
        .hasMessageContaining("VNPAY");
    assertThat(repo.booking(id).orElseThrow().foodTotal()).isZero();
    assertThat(payments.ipn(callbackParameters(reference, "24", "0"))).isEqualTo("00");
    assertThatThrownBy(() -> service.editItems(id, customer, Map.of(1L, 1), ""))
        .hasMessageContaining("VNPAY");
    assertThat(repo.booking(id).orElseThrow().deposit()).isEqualTo(100000);
  }

  @Test
  void realLoginRegistrationAndSignedGatewayCallbackWork() throws Exception {
    mvc.perform(
            post("/login")
                .with(csrf())
                .param("email", customer.email())
                .param("password", "MocDemo123!"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/book"));
    mvc.perform(
            post("/register")
                .with(csrf())
                .param("email", "new@example.com")
                .param("password", "NewPass123!")
                .param("fullName", "Khách mới")
                .param("phone", "0901234567"))
        .andExpect(redirectedUrl("/login?registered"));
    assertThat(repo.account("new@example.com").orElseThrow().role()).isEqualTo("CUSTOMER");
    String id = create(baseline.plusHours(4));
    var params = new TreeMap<String, String>();
    params.put("vnp_TmnCode", "TESTCODE");
    params.put("vnp_TxnRef", startPayment(id));
    params.put("vnp_Amount", Long.toString(repo.booking(id).orElseThrow().deposit() * 100));
    params.put("vnp_ResponseCode", "00");
    params.put("vnp_TransactionStatus", "00");
    params.put("vnp_TransactionNo", "98765");
    var builder = get("/payment/vnpay/ipn");
    params.forEach(builder::param);
    builder.param("vnp_SecureHash", signature(params));
    mvc.perform(builder).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("00"));
    assertThat(repo.booking(id).orElseThrow().status()).isEqualTo(BookingStatus.CONFIRMED);
    mvc.perform(get("/payment/vnpay/ipn").param("vnp_SecureHash", "fake"))
        .andExpect(jsonPath("$.RspCode").value("97"));
  }

  @Test
  void vnpayCheckoutSignsStoredAmountAndReusesPendingAttempt() {
    String id = create(baseline.plusHours(4));
    clock.set(baseline.plusMinutes(1));
    String url = payments.start(id, customer, "127.0.0.1");
    assertThat(url).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
    var params = urlParameters(url);
    assertThat(gateway.verified(params)).isTrue();
    assertThat(params.get("vnp_Amount")).isEqualTo(Long.toString(repo.booking(id).orElseThrow().deposit() * 100));
    assertThat(params.get("vnp_CreateDate")).isEqualTo("20260921100100");
    assertThat(params.get("vnp_ExpireDate")).isEqualTo("20260921101500");
    assertThat(params.get("vnp_TxnRef")).matches("[a-f0-9]{32}");
    assertThat(startPayment(id)).isEqualTo(params.get("vnp_TxnRef"));
    assertThat(payments.attempts(id)).hasSize(1);
    assertThat(
            new vn.edu.giavien.service.VnpayGateway(
                    "", "", "http://localhost:8080/payment/vnpay/return")
                .configured())
        .isFalse();
  }

  @Test
  void browserReturnCannotConfirmButSignedIpnDoesExactlyOnce() throws Exception {
    String id = create(baseline.plusHours(4));
    var params = callbackParameters(startPayment(id), "00", "1234");
    assertThat(payments.browserReturn(params).waiting()).isTrue();
    assertThat(repo.booking(id).orElseThrow().paymentStatus()).isEqualTo(PaymentStatus.UNPAID);
    var browser = get("/payment/vnpay/return");
    params.forEach(browser::param);
    mvc.perform(browser)
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Đang chờ xác nhận")));
    assertThat(payments.ipn(params)).isEqualTo("00");
    assertThat(payments.ipn(params)).isEqualTo("02");
    assertThat(repo.booking(id).orElseThrow().paymentStatus()).isEqualTo(PaymentStatus.PAID);
    assertThat(payments.browserReturn(params).waiting()).isFalse();
    assertThat(payments.attempts(id).getFirst().status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void signedFailurePermitsNewAttemptAndKeepsDepositUnpaid() throws Exception {
    String id = create(baseline.plusHours(4));
    String first = startPayment(id);
    var cancelled = callbackParameters(first, "24", "0");
    assertThat(payments.browserReturn(cancelled).message()).contains("hủy");
    assertThat(payments.ipn(cancelled)).isEqualTo("00");
    assertThat(payments.ipn(cancelled)).isEqualTo("02");
    assertThat(repo.booking(id).orElseThrow().paymentStatus()).isEqualTo(PaymentStatus.UNPAID);
    String retry = startPayment(id);
    assertThat(retry).isNotEqualTo(first);
    assertThat(payments.statusFor(id, customer).waiting()).isTrue();
    assertThat(payments.ipn(callbackParameters(retry, "00", "6789"))).isEqualTo("00");
    assertThat(repo.booking(id).orElseThrow().status()).isEqualTo(BookingStatus.CONFIRMED);
  }

  @Test
  void invalidSignatureMerchantAmountUnknownAndMalformedCallbacksAreRejected() throws Exception {
    String id = create(baseline.plusHours(4));
    var params = callbackParameters(startPayment(id), "00", "1234");
    params.put("vnp_Amount", "20000000");
    assertThat(payments.ipn(params)).isEqualTo("97");
    params.remove("vnp_SecureHash");
    params.put("vnp_SecureHash", signature(params));
    assertThat(payments.ipn(params)).isEqualTo("04");
    params.put("vnp_Amount", "invalid");
    params.remove("vnp_SecureHash");
    params.put("vnp_SecureHash", signature(params));
    assertThat(payments.ipn(params)).isEqualTo("04");
    var unknown = callbackParameters("f".repeat(32), "00", "1234");
    assertThat(payments.ipn(unknown)).isEqualTo("01");
    var wrongMerchant = callbackParameters(startPayment(id), "00", "1234");
    wrongMerchant.put("vnp_TmnCode", "OTHER123");
    wrongMerchant.remove("vnp_SecureHash");
    wrongMerchant.put("vnp_SecureHash", signature(wrongMerchant));
    assertThat(payments.ipn(wrongMerchant)).isEqualTo("97");
    var malformed = callbackParameters(startPayment(id), "00", "1234");
    malformed.remove("vnp_TransactionStatus");
    malformed.remove("vnp_SecureHash");
    malformed.put("vnp_SecureHash", signature(malformed));
    assertThat(payments.ipn(malformed)).isEqualTo("99");
    var request = get("/payment/vnpay/ipn");
    callbackParameters(startPayment(id), "00", "1234").forEach(request::param);
    mvc.perform(request.param("vnp_Amount", "1")).andExpect(jsonPath("$.RspCode").value("97"));
    assertThat(repo.booking(id).orElseThrow().paymentStatus()).isEqualTo(PaymentStatus.UNPAID);
  }

  @Test
  void lateIpnQueuesRefundAndCannotReclaimAnotherCustomersHold() throws Exception {
    String id = create(baseline.plusHours(4));
    var callback = callbackParameters(startPayment(id), "00", "3456");
    clock.set(baseline.plusMinutes(16));
    String replacement = create(baseline.plusHours(4));
    assertThatThrownBy(() -> startPayment(id)).hasMessageContaining("hết hạn");
    assertThat(payments.ipn(callback)).isEqualTo("00");
    assertThat(repo.booking(id).orElseThrow().paymentStatus())
        .isEqualTo(PaymentStatus.REFUND_PENDING);
    assertThat(repo.booking(replacement).orElseThrow().status()).isEqualTo(BookingStatus.PENDING);
    assertThat(payments.browserReturn(callback).title()).contains("chờ hoàn");
  }

  @Test
  void secondDistinctSuccessfulTransactionIsTrackedForRefund() throws Exception {
    String id = create(baseline.plusHours(4));
    String first = startPayment(id);
    payments.ipn(callbackParameters(first, "24", "0"));
    String retry = startPayment(id);
    payments.ipn(callbackParameters(first, "00", "4567"));
    var extra = callbackParameters(retry, "00", "8910");
    assertThat(payments.ipn(extra)).isEqualTo("00");
    assertThat(payments.ipn(extra)).isEqualTo("02");
    assertThat(payments.attempts(id))
        .anyMatch(a -> a.id().equals(retry) && a.status().equals("EXTRA_REFUND_PENDING"));
    mvc.perform(get("/bookings/" + id).with(user(staff.email()).roles("STAFF")))
        .andExpect(status().isOk())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Đã nhận thêm một khoản cọc")));
    assertThatThrownBy(() -> payments.recordExtraRefund(id, retry, customer, "BANK-EXTRA"))
        .hasMessageContaining("nhân viên");
    payments.recordExtraRefund(id, retry, staff, "BANK-EXTRA");
    assertThat(payments.attempts(id))
        .anyMatch(a -> a.id().equals(retry) && a.status().equals("REFUNDED"));
    assertThat(repo.booking(id).orElseThrow().paymentStatus()).isEqualTo(PaymentStatus.PAID);
  }

  @Test
  void paymentEndpointsRequireOwnerAndCsrfAndHaveNoDemoBypass() throws Exception {
    String id = create(baseline.plusHours(4));
    mvc.perform(post("/bookings/" + id + "/pay").with(user(customer.email()).roles("CUSTOMER")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/bookings/" + id + "/pay")
                .with(user(customer.email()).roles("CUSTOMER"))
                .with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            header()
                .string(
                    "Location", org.hamcrest.Matchers.startsWith("https://sandbox.vnpayment.vn/")));
    mvc.perform(
            post("/bookings/" + id + "/demo-pay")
                .with(user(customer.email()).roles("CUSTOMER"))
                .with(csrf()))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/bookings/" + id + "/payment-status"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/bookings/" + id + "/payment-status")
                .with(user(customer.email()).roles("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    service.register("payment-other@example.com", "OtherPass123!", "Khách khác", "0901234567");
    mvc.perform(
            get("/api/bookings/" + id + "/payment-status")
                .with(user("payment-other@example.com").roles("CUSTOMER")))
        .andExpect(status().isForbidden());
    assertThatThrownBy(
            () -> payments.start(id, service.account("payment-other@example.com"), "127.0.0.1"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void missingGatewayConfigurationDoesNotCreatePaymentAttemptOrChangeDeposit() {
    var missing = new vn.edu.giavien.service.VnpayGateway("", "", "");
    assertThat(missing.configured()).isFalse();
    for (String url :
        List.of(
            "", "payment/return", "javascript:alert(1)", "https://user:pass@example.com/return")) {
      assertThat(
              new vn.edu.giavien.service.VnpayGateway("TESTCODE", "test-secret", url).configured())
          .isFalse();
    }
    String id = create(baseline.plusHours(4));
    var paymentService = new vn.edu.giavien.service.VnpayPaymentService(repo, service, missing);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .execute(status -> paymentService.start(id, customer, "127.0.0.1")))
        .hasMessageContaining("chưa sẵn sàng");
    assertThat(payments.attempts(id)).isEmpty();
    assertThat(repo.booking(id).orElseThrow().paymentStatus()).isEqualTo(PaymentStatus.UNPAID);
  }

  private String signature(Map<String, String> params) throws Exception {
    StringJoiner joined = new StringJoiner("&");
    params.forEach(
        (key, value) ->
            joined.add(
                URLEncoder.encode(key, StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(value, StandardCharsets.UTF_8)));
    Mac mac = Mac.getInstance("HmacSHA512");
    mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
    return HexFormat.of()
        .formatHex(mac.doFinal(joined.toString().getBytes(StandardCharsets.UTF_8)));
  }
}
