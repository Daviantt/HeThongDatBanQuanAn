package vn.edu.giavien;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.giavien.data.RestaurantRepository;
import vn.edu.giavien.domain.Models.*;
import vn.edu.giavien.service.BookingService;
import vn.edu.giavien.service.TableBoardService;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:table-board-test;DB_CLOSE_DELAY=-1",
    "app.demo=true"
})
@AutoConfigureMockMvc
@Import(BookingIntegrationTest.TimeConfig.class)
@Transactional
class TableBoardIntegrationTest {
  @Autowired RestaurantRepository repo;
  @Autowired BookingService service;
  @Autowired TableBoardService board;
  @Autowired BookingIntegrationTest.MutableClock clock;
  @Autowired MockMvc mvc;
  final LocalDateTime baseline = LocalDateTime.of(2026, 9, 21, 10, 0);
  Account customer, staff;

  @BeforeEach
  void setup() {
    clock.set(baseline);
    customer = repo.account("khach@moc.local").orElseThrow();
    staff = repo.account("nhanvien@moc.local").orElseThrow();
  }

  String create(LocalDateTime start, List<Long> tables) {
    return service.create(customer, new BookingService.BookingInput(
        start, null, tables.size() == 1 ? 2 : 8, tables, Map.of(1L, 2), ""));
  }

  void pay(String id) {
    assertThat(service.gatewayPayment(id, repo.booking(id).orElseThrow().deposit(), "TEST-" + id, true)).isEqualTo("00");
  }

  TableBoardService.TableCard card(long id) {
    return board.board(clock.instant().atZone(clock.getZone()).toLocalDate()).tables().stream()
        .filter(t -> t.table().id() == id).findFirst().orElseThrow();
  }

  String fragment() throws Exception {
    return mvc.perform(get("/staff/tables/fragment").with(user(staff.email()).roles("STAFF")))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void customerCanSearchAndBookWithoutEndTimeAndBothTablesAppearInLiveFragment() throws Exception {
    mvc.perform(get("/api/availability").param("startAt", "2026-09-21T12:00").param("guests", "8")
        .with(user(customer.email()).roles("CUSTOMER"))).andExpect(status().isOk());
    mvc.perform(post("/api/bookings").with(user(customer.email()).roles("CUSTOMER")).with(csrf())
        .contentType("application/json").content("""
            {"startAt":"2026-09-21T12:00","guests":8,"tableIds":[1,4],"items":{"1":2},"notes":""}
            """)).andExpect(status().isCreated());
    var booking = repo.bookings(customer.id()).getFirst();
    assertThat(booking.endAt()).isNull();
    assertThat(fragment()).contains(booking.code(), customer.fullName(), "Giữ bàn · Chờ cọc");
    assertThat(card(1).state()).isEqualTo("HELD");
    assertThat(card(4).state()).isEqualTo("HELD");
    pay(booking.id());
    assertThat(card(1).state()).isEqualTo("RESERVED");
    assertThat(fragment()).contains("Đã đặt · Chờ khách");
    mvc.perform(get("/bookings/" + booking.id()).with(user(customer.email()).roles("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Giờ kết thúc mới"))));
  }

  @Test
  void staffFinishesMergedTablesAndCleanupStartsAtActualClickNotLegacyEnd() throws Exception {
    String id = create(baseline.plusHours(1), List.of(1L, 4L));
    pay(id);
    // A historical estimated finish must never free a seated party.
    repo.jdbc().update("UPDATE reservation SET end_at=? WHERE id=?", baseline.plusHours(2), id);
    clock.set(baseline.plusHours(1));
    service.staffAction(id, staff, "checkin", "");
    clock.set(baseline.plusHours(6));
    assertThat(card(1).state()).isEqualTo("SEATED");
    assertThatThrownBy(() -> create(baseline.plusHours(7), List.of(1L))).hasMessageContaining("Bàn vừa");
    mvc.perform(post("/staff/tables/bookings/" + id + "/action")
        .with(user(staff.email()).roles("STAFF")).with(csrf())
        .param("action", "complete").param("date", "2026-09-21"))
        .andExpect(redirectedUrl("/staff/tables?date=2026-09-21"));
    assertThat(repo.booking(id).orElseThrow().releasedAt()).isEqualTo(baseline.plusHours(6));
    assertThat(card(1).state()).isEqualTo("CLEANING");
    assertThat(card(4).state()).isEqualTo("CLEANING");
    assertThat(card(1).cleaningUntil()).isEqualTo(baseline.plusHours(6).plusMinutes(15));
    assertThatThrownBy(() -> create(baseline.plusHours(6).plusMinutes(14), List.of(1L)))
        .hasMessageContaining("Bàn vừa");
    assertThatThrownBy(() -> service.staffAction(id, staff, "complete", "")).hasMessageContaining("nhận bàn");
    clock.set(baseline.plusHours(6).plusMinutes(15));
    assertThat(card(1).state()).isEqualTo("FREE");
    assertThat(create(clock.instant().atZone(clock.getZone()).toLocalDateTime().plusMinutes(1), List.of(1L))).isNotBlank();
  }

  @Test
  void noEstimatedEndCanBypassTheSameDayHoldAndFutureDatesRemainSeparate() {
    String id = create(baseline.plusHours(2), List.of(1L));
    pay(id);
    assertThatThrownBy(() -> create(baseline.plusHours(8), List.of(1L))).hasMessageContaining("Bàn vừa");
    assertThatThrownBy(() -> create(baseline.plusHours(1), List.of(1L))).hasMessageContaining("Bàn vừa");
    String tomorrow = create(baseline.plusDays(1).plusHours(2), List.of(1L));
    assertThat(card(1).upcoming()).extracting(Booking::id).contains(tomorrow);
    service.cancel(id, staff, true);
    assertThat(card(1).state()).isEqualTo("FREE");
    assertThat(create(baseline.plusHours(8), List.of(1L))).isNotBlank();
  }

  @Test
  void expiredHoldsAndNoShowsReleaseCardsAndRebookingIsReflected() throws Exception {
    String id = create(baseline.plusHours(2), List.of(1L));
    clock.set(baseline.plusMinutes(15));
    fragment(); // Refresh expires unpaid holds as well as reflecting new bookings.
    assertThat(repo.booking(id).orElseThrow().status()).isEqualTo(BookingStatus.EXPIRED);
    assertThat(card(1).state()).isEqualTo("FREE");
    String replacement = create(baseline.plusHours(2), List.of(1L));
    pay(replacement);
    clock.set(baseline.plusHours(2).plusMinutes(15));
    service.staffAction(replacement, staff, "noshow", "");
    assertThat(card(1).state()).isEqualTo("FREE");
    assertThat(repo.booking(replacement).orElseThrow().paymentStatus()).isEqualTo(PaymentStatus.FORFEITED);
  }

  @Test
  void upcomingReservationsAppearWithoutChangingTodayAndBoardIsRestricted() throws Exception {
    String id = create(baseline.plusDays(1), List.of(1L));
    assertThat(card(1).state()).isEqualTo("FREE");
    assertThat(card(1).upcoming()).extracting(Booking::id).contains(id);
    assertThat(fragment()).contains("lượt đặt ngày tiếp theo", customer.fullName());
    for (String path : List.of("/staff/tables", "/staff/tables/fragment")) {
      mvc.perform(get(path)).andExpect(status().is3xxRedirection());
      mvc.perform(get(path).with(user(customer.email()).roles("CUSTOMER"))).andExpect(status().isForbidden());
      mvc.perform(get(path).with(user(staff.email()).roles("STAFF"))).andExpect(status().isOk());
      mvc.perform(get(path).with(user("admin@moc.local").roles("ADMIN"))).andExpect(status().isOk());
    }
    mvc.perform(post("/staff/tables/bookings/" + id + "/action")
        .with(user(staff.email()).roles("STAFF")).param("action", "complete").param("date", "2026-09-21"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/staff/tables/bookings/" + id + "/action")
        .with(user(customer.email()).roles("CUSTOMER")).with(csrf())
        .param("action", "complete").param("date", "2026-09-21"))
        .andExpect(status().isForbidden());
  }

  @Test
  void seatedTableBlocksAnotherCheckinUntilStaffFinishesAndCleanupEnds() {
    String id = create(baseline.plusHours(1), List.of(1L));
    pay(id);
    String tomorrow = create(baseline.plusDays(1).plusHours(1), List.of(1L));
    pay(tomorrow);
    clock.set(baseline.plusHours(1));
    service.staffAction(id, staff, "checkin", "");
    clock.set(baseline.plusDays(1).plusHours(1));
    assertThatThrownBy(() -> service.staffAction(tomorrow, staff, "checkin", "")).hasMessageContaining("đang phục vụ");
    service.staffAction(id, staff, "complete", "");
    assertThatThrownBy(() -> service.staffAction(tomorrow, staff, "checkin", "")).hasMessageContaining("đang dọn");
    clock.set(baseline.plusDays(1).plusHours(1).plusMinutes(15));
    service.staffAction(tomorrow, staff, "checkin", "");
    assertThat(repo.booking(tomorrow).orElseThrow().status()).isEqualTo(BookingStatus.SEATED);
  }
}
