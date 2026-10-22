package vn.edu.giavien;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.giavien.data.RestaurantRepository;
import vn.edu.giavien.service.BookingService;
import vn.edu.giavien.service.StatisticsService;
import vn.edu.giavien.service.StatisticsService.Grouping;
import vn.edu.giavien.service.TakeawayService;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:statistics-test;DB_CLOSE_DELAY=-1",
    "app.demo=true"
})
@AutoConfigureMockMvc
@Transactional
class StatisticsIntegrationTest {
  @Autowired RestaurantRepository repo;
  @Autowired StatisticsService statistics;
  @Autowired BookingService bookings;
  @Autowired TakeawayService takeaway;
  @Autowired MockMvc mvc;
  @Autowired Clock clock;

  private static final LocalDate START = LocalDate.of(2026, 1, 1);
  private static final LocalDate END = LocalDate.of(2026, 12, 31);

  private String booking(LocalDateTime completed, String status, long price, int quantity, int percent) {
    String id = UUID.randomUUID().toString();
    long user = repo.account("khach@giavien.local").orElseThrow().id();
    repo.jdbc().update("""
        INSERT INTO reservation(id,user_id,start_at,end_at,guests,notes,status,payment_status,
          deposit,created_at,hold_until,released_at,promotion_minimum_guests,promotion_percent)
        VALUES(?,?,?,?,4,'',?,'PAID',100000,?,?,?,4,?)
        """, id, user, completed.minusDays(2), completed.minusDays(2).plusHours(2), status,
        completed.minusDays(3), completed.minusDays(3).plusMinutes(15), completed, percent);
    repo.jdbc().update("INSERT INTO order_line VALUES(?,1,'Tên món tại lúc đặt',?,?)", id, price, quantity);
    return id;
  }

  private String takeaway(LocalDateTime completed, String status, int quantity) {
    String id = UUID.randomUUID().toString();
    long user = repo.account("khach@giavien.local").orElseThrow().id();
    repo.jdbc().update("""
        INSERT INTO takeaway_order(id,user_id,customer_name,customer_phone,pickup_at,total,
          payment_method,status,created_at,completed_at)
        VALUES(?,?,'Khách thử','0901234567',?,?,'CASH',?,?,?)
        """, id, user, completed.minusDays(1), quantity * 1000L, status, completed.minusDays(2), completed);
    repo.jdbc().update("INSERT INTO takeaway_order_line VALUES(?,1,'Tên món tại lúc đặt',1000,?)", id, quantity);
    return id;
  }

  @Test
  void combinesBothChannelsWithSavedPricesAndDiscountWithoutAddingDeposits() {
    booking(START.atStartOfDay(), "COMPLETED", 1001, 3, 10); // 3003 - floor(300.3) = 2703
    takeaway(START.atTime(23, 59, 59), "COMPLETED", 2);
    booking(START.plusDays(1).atStartOfDay(), "COMPLETED", 900000, 5, 0);
    takeaway(START.minusDays(1).atTime(23, 59, 59), "COMPLETED", 9);
    for (String status : List.of("PENDING", "CONFIRMED", "SEATED", "CANCELLED", "EXPIRED", "NO_SHOW"))
      booking(START.atTime(12, 0), status, 500000, 20, 0);
    for (String status : List.of("AWAITING_PAYMENT", "WAITING_PICKUP", "PREPARING", "READY",
        "REFUND_PENDING", "CANCELLED", "EXPIRED"))
      takeaway(START.atTime(12, 0), status, 20);
    repo.jdbc().update("UPDATE menu_item SET price=999999,available=FALSE WHERE id=1");

    var result = statistics.report(START, START, Grouping.DAY);
    assertThat(result.dineIn()).isEqualTo(2703);
    assertThat(result.takeaway()).isEqualTo(2000);
    assertThat(result.total()).isEqualTo(4703);
    assertThat(result.orders()).isEqualTo(2);
    assertThat(result.quantity()).isEqualTo(5);
    assertThat(result.periods()).singleElement().satisfies(row -> {
      assertThat(row.period()).isEqualTo("01/01/2026");
      assertThat(row.total()).isEqualTo(4703);
    });
    assertThat(result.dishes().getFirst().orders()).isEqualTo(2);
    assertThat(result.dishes().getFirst().dish().available()).isFalse();
  }

  @Test
  void groupsDaysMonthsAndYearsWithoutMergingTheSameMonthAcrossYears() {
    takeaway(LocalDateTime.of(2025, 1, 10, 12, 0), "COMPLETED", 1);
    takeaway(LocalDateTime.of(2026, 1, 10, 12, 0), "COMPLETED", 2);
    takeaway(LocalDateTime.of(2026, 1, 11, 12, 0), "COMPLETED", 3);
    takeaway(LocalDateTime.of(2026, 2, 10, 12, 0), "COMPLETED", 4);
    LocalDate from = START.minusYears(1);
    assertThat(statistics.report(from, END, Grouping.DAY).periods()).hasSize(4);
    assertThat(statistics.report(from, END, Grouping.MONTH).periods())
        .extracting(StatisticsService.RevenueRow::total).containsExactly(1000L, 5000L, 4000L);
    assertThat(statistics.report(from, END, Grouping.YEAR).periods())
        .extracting(StatisticsService.RevenueRow::total).containsExactly(1000L, 9000L);
  }

  @Test
  void bestsellerRequires701OrdersWithinOneCalendarMonthAndWithinTheFilter() throws Exception {
    var january = START.atTime(12, 0);
    // 700 distinct orders; two portions each must not be mistaken for 1,400 orders.
    for (int i = 0; i < 700; i++) takeaway(january, "COMPLETED", 2);
    takeaway(january.plusMonths(1), "COMPLETED", 1);
    takeaway(january.minusYears(1), "COMPLETED", 1);
    assertThat(statistics.report(START.minusYears(1), END, Grouping.YEAR).bestsellers()).isZero();
    booking(january.plusDays(1), "COMPLETED", 1000, 3, 0);
    var report = statistics.report(START, END, Grouping.MONTH);
    var dish = report.dishes().getFirst();
    assertThat(dish.quantity()).isEqualTo(1404);
    assertThat(dish.orders()).isEqualTo(702);
    assertThat(dish.bestMonths()).singleElement().satisfies(month -> {
      assertThat(month.month()).isEqualTo(YearMonth.of(2026, 1));
      assertThat(month.orders()).isEqualTo(701);
    });
    assertThat(statistics.report(START, START, Grouping.DAY).bestsellers()).isZero();
    assertThat(statistics.report(START.plusMonths(1), END, Grouping.YEAR).bestsellers()).isZero();
    mvc.perform(get("/admin/statistics").with(user("quanly@giavien.local").roles("ADMIN"))
        .param("startDate", START.toString()).param("endDate", END.toString()))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("★ Bán chạy")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("01/2026: 701 đơn")));
  }

  @Test
  void dishCreatedThroughAdminFormAppearsImmediatelyWithZeroSales() throws Exception {
    mvc.perform(post("/admin/dishes").with(user("quanly@giavien.local").roles("ADMIN")).with(csrf())
        .param("id", "0").param("name", "Món mới cho thống kê").param("description", "")
        .param("category", "Món chính").param("price", "50000").param("available", "true"))
        .andExpect(status().is3xxRedirection());
    var report = statistics.report(START, END, Grouping.MONTH);
    assertThat(report.total()).isZero();
    assertThat(report.periods()).isEmpty();
    assertThat(report.dishes()).filteredOn(row -> row.dish().name().equals("Món mới cho thống kê"))
        .singleElement().satisfies(row -> {
          assertThat(row.quantity()).isZero();
          assertThat(row.orders()).isZero();
          assertThat(row.bestseller()).isFalse();
        });
    mvc.perform(get("/admin/statistics").with(user("quanly@giavien.local").roles("ADMIN"))
        .param("startDate", START.toString()).param("endDate", END.toString()))
        .andExpect(status().isOk()).andExpect(view().name("admin-statistics"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Món mới cho thống kê")));
  }

  @Test
  void rendersPopulatedReportForAllGroupingsAndRestrictsAccessToManagers() throws Exception {
    takeaway(START.atTime(12, 0), "COMPLETED", 2);
    for (var grouping : Grouping.values())
      mvc.perform(get("/admin/statistics").with(user("quanly@giavien.local").roles("ADMIN"))
          .param("startDate", START.toString()).param("endDate", END.toString())
          .param("groupBy", grouping.name()))
          .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("2.000 ₫")));
    mvc.perform(get("/admin/statistics")).andExpect(status().is3xxRedirection());
    for (String role : List.of("CUSTOMER", "STAFF"))
      mvc.perform(get("/admin/statistics").with(user("test").roles(role))).andExpect(status().isForbidden());
    mvc.perform(get("/admin/statistics").with(user("quanly@giavien.local").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(model().attribute("startDate", LocalDate.now(clock).withDayOfMonth(1).toString()))
        .andExpect(model().attribute("endDate", LocalDate.now(clock).toString()));
  }

  @Test
  void invalidFiltersReturnHelpfulErrorsInsteadOfAnUnfilteredReport() throws Exception {
    for (String[] params : List.of(
        new String[]{"2026-02-30", "2026-03-01", "DAY"},
        new String[]{"2026-02-01", "2026-01-01", "MONTH"},
        new String[]{"", "2026-01-01", "MONTH"},
        new String[]{"2026-01-01", "2026-01-02", "INVALID"},
        new String[]{"1800-01-01", "2026-01-02", "YEAR"}))
      mvc.perform(get("/admin/statistics").with(user("quanly@giavien.local").roles("ADMIN"))
          .param("startDate", params[0]).param("endDate", params[1]).param("groupBy", params[2]))
          .andExpect(status().isBadRequest()).andExpect(model().attributeExists("filterError"))
          .andExpect(model().attributeDoesNotExist("report")).andExpect(view().name("admin-statistics"));
  }

  @Test
  void oldCompletedOrdersRemainVisibleAndNewTakeawaysUseActualCompletionTime() {
    String legacy = takeaway(START.atTime(12, 0), "COMPLETED", 2);
    repo.jdbc().update("UPDATE takeaway_order SET completed_at=NULL,pickup_at=? WHERE id=?", START.atStartOfDay(), legacy);
    String oldBooking = booking(START.atTime(12, 0), "COMPLETED", 1000, 1, 0);
    repo.jdbc().update("UPDATE reservation SET released_at=NULL,end_at=? WHERE id=?", START.atTime(23, 0), oldBooking);
    assertThat(statistics.report(START, START, Grouping.DAY).total()).isEqualTo(3000);

    String ready = takeaway(START.atTime(12, 0), "READY", 1);
    repo.jdbc().update("UPDATE takeaway_order SET completed_at=NULL WHERE id=?", ready);
    var before = bookings.now();
    takeaway.staffAction(ready, repo.account("nhanvien@giavien.local").orElseThrow(), "complete", "");
    var completed = repo.jdbc().queryForObject("SELECT completed_at FROM takeaway_order WHERE id=?",
        LocalDateTime.class, ready);
    // H2 TIMESTAMP rounds nanoseconds to microseconds.
    assertThat(completed).isBetween(before.minusNanos(1000), bookings.now().plusNanos(1000));
    assertThat(repo.takeaway(ready).orElseThrow().paidAt()).isNotNull();
    assertThat(statistics.report(completed.toLocalDate(), completed.toLocalDate(), Grouping.DAY).takeaway())
        .isGreaterThanOrEqualTo(1000);
  }
}
