package vn.edu.giavien.domain;

import java.time.LocalDateTime;
import vn.edu.giavien.domain.Models.*;

/** Quy tắc thời gian dùng chung cho giao diện và các thao tác nghiệp vụ. */
public final class BookingPolicy {
  private BookingPolicy() {}

  public static boolean refundable(LocalDateTime now, LocalDateTime start) {
    return !now.isAfter(start.minusHours(3));
  }

  public static boolean editable(Booking booking, LocalDateTime now) {
    return (booking.status() == BookingStatus.PENDING
            || booking.status() == BookingStatus.CONFIRMED)
        && !now.isAfter(booking.startAt().minusHours(2));
  }

  public static boolean cancellable(Booking booking, LocalDateTime now) {
    return (booking.status() == BookingStatus.PENDING
            || booking.status() == BookingStatus.CONFIRMED)
        && now.isBefore(booking.startAt());
  }

  public static int requiredTables(int guests) {
    return (guests + 3) / 4;
  }

  public static boolean overlaps(
      LocalDateTime start,
      LocalDateTime end,
      LocalDateTime otherStart,
      LocalDateTime otherEnd,
      int cleanupMinutes) {
    return start.isBefore(otherEnd.plusMinutes(cleanupMinutes))
        && end.plusMinutes(cleanupMinutes).isAfter(otherStart);
  }
}
