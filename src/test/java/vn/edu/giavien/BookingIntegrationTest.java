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

  @BeforeEach
  void setup() {
    clock.set(baseline);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              repo.lockSchedule();
              for (String table :
                  List.of(
                      "audit_event",
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
    service.demoPay(id, customer);
    return id;
  }

  @Test
  void gardenLayoutHasTenNumberedTablesAndVerticalBookingsBlockEveryTable() {
    assertThat(repo.tables().stream().filter(t -> t.floor() == 1).toList())
        .extracting(DiningTable::code)
        .containsExactly("B01", "B02", "B03", "B04", "B05", "B06", "B07", "B08", "B09", "B10");
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
    assertThat(repo.booking(id).orElseThrow().deposit()).isEqualTo(300000);
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
              assertThat(repo.tables()).hasSize(20);
              assertThat(repo.combinations()).hasSize(32);
              service.saveCombination(admin, "1,4", true);
              seed.run();
              assertThat(repo.combinations()).doesNotContain(List.of(1L, 4L));
              assertThat(repo.tables()).extracting(DiningTable::code).contains("B05", "B10");
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
    assertThat(repo.booking(booking).orElseThrow().deposit()).isEqualTo(200000);
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
              assertThat(repo.tables()).hasSize(20);
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
    assertThat(repo.booking(id).orElseThrow().deposit()).isEqualTo(300000);
    service.updateSettings(admin, 200000, 15, 15, 15);
    assertThat(repo.booking(id).orElseThrow().deposit()).isEqualTo(300000);
    assertThatThrownBy(
            () -> service.create(customer, input(baseline.plusHours(5), 9, List.of(4L, 5L))))
        .hasMessageContaining("Số bàn");
    assertThatThrownBy(
            () -> service.create(customer, input(baseline.plusHours(5), 8, List.of(4L, 9L))))
        .hasMessageContaining("tổ hợp");
  }

  @Test
  void overlapIncludesCleanupAndExpiresHolds() {
    String id = create(baseline.plusHours(2)); // 12:00–14:00
    assertThatThrownBy(() -> create(baseline.plusHours(4).plusMinutes(14)))
        .hasMessageContaining("Bàn vừa");
    assertThat(create(baseline.plusHours(4).plusMinutes(15))).isNotBlank();
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
    assertThat(service.gatewayPayment(id, 100000, "12345", true)).isEqualTo("00");
    Booking late = repo.booking(id).orElseThrow();
    assertThat(late.status()).isEqualTo(BookingStatus.EXPIRED);
    assertThat(late.paymentStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
    assertThat(service.gatewayPayment(id, 100000, "12345", true)).isEqualTo("02");
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
    params.put("vnp_TxnRef", id);
    params.put("vnp_Amount", "10000000");
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
