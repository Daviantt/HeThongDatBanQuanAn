package vn.edu.giavien;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.giavien.data.RestaurantRepository;
import vn.edu.giavien.domain.Models.*;
import vn.edu.giavien.service.*;

@SpringBootTest(properties = {"app.demo=true", "app.payment.demo-enabled=true",
    "app.vnpay.tmn-code=", "app.vnpay.hash-secret=", "test.suite=stock-return"})
@AutoConfigureMockMvc
@Import(BookingIntegrationTest.TimeConfig.class)
class StockReturnIntegrationTest extends DatabaseIntegrationTest {
  @Autowired RestaurantRepository repo;
  @Autowired BookingService bookings;
  @Autowired TakeawayService takeaway;
  @Autowired BookingIntegrationTest.MutableClock clock;
  @Autowired MockMvc mvc;
  Account customer, staff;
  final LocalDateTime now = LocalDateTime.of(2026, 9, 21, 10, 0);

  @BeforeEach void reset() {
    clock.set(now);
    for (String table : List.of("takeaway_demo_payment", "takeaway_order_line", "takeaway_order"))
      repo.jdbc().update("DELETE FROM " + table);
    repo.jdbc().update("UPDATE menu_item SET stock_quantity=10,available=TRUE");
    customer = bookings.account("khach@giavien.local");
    staff = bookings.account("nhanvien@giavien.local");
  }

  String create(String method, Map<Long, Integer> items) {
    Map<Long, Long> prices = new HashMap<>();
    takeaway.quote(items).lines().forEach(line -> prices.put(line.menuItemId(), line.unitPrice()));
    return takeaway.create(customer, new TakeawayService.Input("Khách hàng", "0901234567",
        now.plusHours(1), method, items, prices));
  }

  Integer stock(long dish) {
    return repo.jdbc().queryForObject("SELECT stock_quantity FROM menu_item WHERE id=?", Integer.class, dish);
  }

  void cancel(String id) { takeaway.staffAction(id, staff, "cancel", ""); }

  @Test void cashCancellationReturnsEachDeductionOnceAndPreservesSaleFlag() throws Exception {
    String id = create("CASH", Map.of(1L, 2, 2L, 3));
    assertThat(stock(1)).isEqualTo(8);
    repo.jdbc().update("UPDATE menu_item SET available=FALSE WHERE id=1");
    cancel(id);
    assertThat(stock(1)).isEqualTo(10);
    assertThat(stock(2)).isEqualTo(10);
    assertThat(repo.dishes().getFirst().saleEnabled()).isFalse();
    assertThat(repo.takeaway(id).orElseThrow().status()).isEqualTo(TakeawayStatus.CANCELLED);
    assertThatThrownBy(() -> cancel(id)).isInstanceOf(IllegalArgumentException.class);
    assertThat(stock(1)).isEqualTo(10);
    mvc.perform(get("/takeaway/orders/" + id).with(user(staff.email()).roles("STAFF")))
        .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Đã hủy")));
  }

  @Test void paidTransferReturnsStockAtCancellationButRefundDoesNotReturnAgain() {
    String id = create("TRANSFER", Map.of(1L, 2));
    String token = takeaway.startDemoPayment(id, customer);
    clock.set(now.plusMinutes(1));
    takeaway.confirmDemoPayment(token);
    takeaway.confirmDemoPayment(token);
    assertThat(stock(1)).isEqualTo(8);
    cancel(id);
    assertThat(stock(1)).isEqualTo(10);
    assertThat(repo.takeaway(id).orElseThrow().status()).isEqualTo(TakeawayStatus.REFUND_PENDING);
    takeaway.staffAction(id, staff, "refund", "REFUND-TEST");
    assertThatThrownBy(() -> takeaway.staffAction(id, staff, "refund", "REFUND-TEST"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(stock(1)).isEqualTo(10);
  }

  @Test void unpaidTransferAndHistoricalOrdersDoNotInventDeduction() {
    cancel(create("TRANSFER", Map.of(1L, 2)));
    assertThat(stock(1)).isEqualTo(10);
    String legacy = create("CASH", Map.of(1L, 2));
    // Model a pre-upgrade row: new columns are defaulted to zero, not backfilled from status.
    repo.jdbc().update("UPDATE takeaway_order_line SET stock_deducted=0 WHERE order_id=?", legacy);
    cancel(legacy);
    assertThat(stock(1)).isEqualTo(8);
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void unmanagedStockNeverCreatesAnArtificialReturn(boolean startManagingLater) {
    repo.jdbc().update("UPDATE menu_item SET stock_quantity=NULL WHERE id=1");
    String id = create("CASH", Map.of(1L, 2));
    if (startManagingLater) repo.jdbc().update("UPDATE menu_item SET stock_quantity=7 WHERE id=1");
    cancel(id);
    assertThat(stock(1)).isEqualTo(startManagingLater ? 7 : null);
  }

  @Test void disablingStockManagementBeforeCancelKeepsNull() {
    String id = create("CASH", Map.of(1L, 2));
    repo.jdbc().update("UPDATE menu_item SET stock_quantity=NULL WHERE id=1");
    cancel(id);
    assertThat(stock(1)).isNull();
  }

  @ParameterizedTest @ValueSource(strings = {"prepare", "ready"})
  void cancellationAfterPreparationDoesNotReturnIngredients(String action) {
    String id = create("CASH", Map.of(1L, 2));
    takeaway.staffAction(id, staff, "prepare", "");
    if (action.equals("ready")) takeaway.staffAction(id, staff, "ready", "");
    cancel(id);
    assertThat(stock(1)).isEqualTo(8);
  }

  @Test void shortageRollsBackAllStockAndDeductionHistoryThenPaymentCanRetry() {
    repo.jdbc().update("UPDATE menu_item SET stock_quantity=1 WHERE id=2");
    assertThatThrownBy(() -> create("CASH", Map.of(1L, 2, 2L, 2))).isInstanceOf(IllegalArgumentException.class);
    assertThat(stock(1)).isEqualTo(10);
    assertThat(repo.takeawayOrders(customer.id())).isEmpty();
    String id = create("TRANSFER", Map.of(1L, 2, 2L, 2));
    String token = takeaway.startDemoPayment(id, customer);
    clock.set(now.plusMinutes(1));
    assertThatThrownBy(() -> takeaway.confirmDemoPayment(token)).isInstanceOf(IllegalArgumentException.class);
    assertThat(stock(1)).isEqualTo(10);
    assertThat(repo.takeaway(id).orElseThrow().paidAt()).isNull();
    assertThat(repo.jdbc().queryForObject("SELECT SUM(stock_deducted) FROM takeaway_order_line WHERE order_id=?", Integer.class, id)).isZero();
    repo.jdbc().update("UPDATE menu_item SET stock_quantity=2 WHERE id=2");
    takeaway.confirmDemoPayment(token);
    cancel(id);
    assertThat(stock(1)).isEqualTo(10);
    assertThat(stock(2)).isEqualTo(2);
  }

  @ParameterizedTest @ValueSource(strings = {"cancel", "prepare"})
  void concurrentActionsAreSerializedByOrderLock(String competingAction) throws Exception {
    String id = create("CASH", Map.of(1L, 2));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var first = pool.submit(() -> { start.await(); cancel(id); return true; });
      var second = pool.submit(() -> {
        start.await();
        try { takeaway.staffAction(id, staff, competingAction, ""); return true; }
        catch (IllegalArgumentException expected) { return false; }
      });
      start.countDown();
      // Duplicate cancel may win first; both outcomes must preserve the invariant.
      boolean cancelled;
      try { cancelled = first.get(15, TimeUnit.SECONDS); }
      catch (ExecutionException error) {
        assertThat(error.getCause()).isInstanceOf(IllegalArgumentException.class);
        cancelled = false;
      }
      boolean otherSucceeded = second.get(15, TimeUnit.SECONDS);
      assertThat(cancelled || otherSucceeded).isTrue();
      assertThat(repo.takeaway(id).orElseThrow().status()).isEqualTo(TakeawayStatus.CANCELLED);
      assertThat(stock(1)).isEqualTo(competingAction.equals("prepare") && otherSucceeded ? 8 : 10);
    }
  }
}
