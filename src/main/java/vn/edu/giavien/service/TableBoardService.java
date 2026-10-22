package vn.edu.giavien.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.giavien.data.RestaurantRepository;
import vn.edu.giavien.domain.Models.*;

@Service
public class TableBoardService {
  private final RestaurantRepository repo;
  private final BookingService bookings;

  public TableBoardService(RestaurantRepository repo, BookingService bookings) {
    this.repo = repo;
    this.bookings = bookings;
  }

  public record TableCard(DiningTable table, String state, String label,
      LocalDateTime cleaningUntil, List<Booking> bookings, List<Booking> upcoming) {}

  public record Board(LocalDate date, LocalDateTime updatedAt, List<TableCard> tables) {
    public long count(String state) { return tables.stream().filter(t -> t.state().equals(state)).count(); }
  }

  @Transactional(readOnly = true)
  public Board board(LocalDate date) {
    LocalDateTime now = bookings.now();
    int cleanup = repo.settings().cleanupMinutes();
    var all = repo.tableBoardBookings(date.atStartOfDay(), date.plusDays(1).atStartOfDay(), now, cleanup);
    var cards = repo.tables().stream().map(table -> {
      var related = all.stream().filter(b -> b.tableIds().contains(table.id())).toList();
      var current = related.stream().filter(b -> b.status() != BookingStatus.COMPLETED)
          .filter(b -> b.startAt().toLocalDate().equals(date)
              || b.status() == BookingStatus.SEATED).toList();
      var upcoming = related.stream().filter(b -> b.status() != BookingStatus.COMPLETED)
          .filter(b -> b.status() != BookingStatus.SEATED && b.startAt().toLocalDate().isAfter(date)).toList();
      LocalDateTime cleaningUntil = related.stream()
          .filter(b -> b.status() == BookingStatus.COMPLETED && b.releasedAt() != null)
          .map(b -> b.releasedAt().plusMinutes(cleanup))
          .filter(end -> date.equals(now.toLocalDate()) && end.isAfter(now))
          .max(LocalDateTime::compareTo).orElse(null);
      String state;
      String label;
      if (current.stream().anyMatch(b -> b.status() == BookingStatus.SEATED)) {
        state = "SEATED"; label = "Đang phục vụ";
      } else if (cleaningUntil != null) {
        state = "CLEANING"; label = "Đang dọn bàn";
      } else if (!table.active()) {
        state = "INACTIVE"; label = "Tạm ngừng";
      } else if (current.stream().anyMatch(b -> b.status() == BookingStatus.CONFIRMED)) {
        state = "RESERVED"; label = "Đã đặt · Chờ khách";
      } else if (!current.isEmpty()) {
        state = "HELD"; label = "Giữ bàn · Chờ cọc";
      } else {
        state = "FREE"; label = "Trống";
      }
      return new TableCard(table, state, label, cleaningUntil, current, upcoming);
    }).toList();
    return new Board(date, now, cards);
  }
}
