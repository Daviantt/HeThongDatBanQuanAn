package vn.edu.giavien.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.giavien.data.RestaurantRepository;
import vn.edu.giavien.domain.Models.*;

@Service
@Transactional
public class DemoPaymentService {
  private final RestaurantRepository repo;
  private final BookingService bookings;
  private final VnpayGateway gateway;
  private final boolean demoEnabled;

  public DemoPaymentService(
      RestaurantRepository repo,
      BookingService bookings,
      VnpayGateway gateway,
      @Value("${app.payment.demo-enabled:false}") boolean demoEnabled) {
    this.repo = repo;
    this.bookings = bookings;
    this.gateway = gateway;
    this.demoEnabled = demoEnabled;
  }

  public boolean enabled() {
    return demoEnabled && !gateway.configured();
  }

  private void requireEnabled() {
    if (!enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  public record Session(String token, String bookingId, LocalDateTime createdAt) {}

  public record State(
      String bookingId,
      String code,
      long amount,
      String status,
      long remainingSeconds,
      String message) {}

  public String start(String bookingId, Account actor) {
    requireEnabled();
    repo.lockSchedule();
    repo.expire(bookings.now());
    Booking booking = bookings.accessible(bookingId, actor);
    if (booking.userId() != actor.id())
      throw new AccessDeniedException("Chỉ chủ lượt đặt được mở thanh toán demo.");
    BookingService.require(
        booking.status() == BookingStatus.PENDING
            && booking.paymentStatus() == PaymentStatus.UNPAID,
        "Lượt đặt không còn chờ thanh toán.");
    var existing =
        repo.jdbc()
            .queryForList(
                "SELECT token FROM demo_payment WHERE reservation_id=?", String.class, bookingId);
    if (!existing.isEmpty()) return existing.getFirst();
    String token = UUID.randomUUID().toString().replace("-", "");
    repo.jdbc()
        .update(
            "INSERT INTO demo_payment(token,reservation_id,created_at) VALUES(?,?,?)",
            token,
            bookingId,
            bookings.now());
    repo.audit(bookingId, actor.email(), "Mở QR demo — không thu tiền thật", bookings.now());
    return token;
  }

  private Session session(String token) {
    requireEnabled();
    if (!token.matches("[a-f0-9]{32}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return repo
        .jdbc()
        .query(
            "SELECT * FROM demo_payment WHERE token=?",
            (r, n) ->
                new Session(
                    r.getString("token"),
                    r.getString("reservation_id"),
                    r.getObject("created_at", LocalDateTime.class)),
            token)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  public boolean isDemoPayment(String bookingId) {
    return repo.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE id=? AND payment_provider='DEMO'",
                Integer.class,
                bookingId)
        > 0;
  }

  public State status(String token) {
    Session session = session(token);
    Booking booking = repo.booking(session.bookingId()).orElseThrow();
    String status, message;
    long seconds = 0;
    if (booking.paymentStatus() != PaymentStatus.UNPAID) {
      status = "PAID";
      message =
          isDemoPayment(booking.id())
              ? "Đã mô phỏng thanh toán thành công. Không có tiền thật được chuyển."
              : "Lượt đặt đã được thanh toán. Mở lượt đặt để xem chi tiết.";
    } else if (booking.status() != BookingStatus.PENDING
        || !bookings.now().isBefore(booking.holdUntil())) {
      status = "CLOSED";
      message = "Lượt đặt đã hủy hoặc hết hạn giữ bàn. Không thể thanh toán demo.";
    } else {
      long millis =
          Duration.between(bookings.now(), session.createdAt().plusSeconds(60)).toMillis();
      seconds = Math.max(0, (millis + 999) / 1000);
      status = seconds > 0 ? "WAITING" : "READY";
      message =
          seconds > 0
              ? "Đợi đủ 1 phút để tự bấm xác nhận thanh toán demo."
              : "Bạn có thể bấm nút bên dưới để mô phỏng thanh toán thành công.";
    }
    return new State(booking.id(), booking.code(), booking.deposit(), status, seconds, message);
  }

  public void confirm(String token) {
    requireEnabled();
    repo.lockSchedule();
    repo.expire(bookings.now());
    State state = status(token);
    if (state.status().equals("PAID")) return;
    BookingService.require(state.status().equals("READY"), state.message());
    repo.jdbc()
        .update(
            "UPDATE reservation SET status='CONFIRMED',payment_status='PAID',"
                + "paid_at=?,payment_provider='DEMO',payment_reference=? WHERE id=?",
            bookings.now(),
            "DEMO-" + token,
            state.bookingId());
    repo.audit(state.bookingId(), "DEMO", "Mô phỏng nhận cọc; xác nhận đặt bàn", bookings.now());
  }
}
