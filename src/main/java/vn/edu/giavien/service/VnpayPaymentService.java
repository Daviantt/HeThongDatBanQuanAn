package vn.edu.giavien.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.giavien.data.RestaurantRepository;
import vn.edu.giavien.domain.Models.*;

@Service
@Transactional
public class VnpayPaymentService {
  private final RestaurantRepository repo;
  private final BookingService bookings;
  private final VnpayGateway gateway;

  public VnpayPaymentService(
      RestaurantRepository repo, BookingService bookings, VnpayGateway gateway) {
    this.repo = repo;
    this.bookings = bookings;
    this.gateway = gateway;
  }

  public record Attempt(
      String id,
      String bookingId,
      long amount,
      LocalDateTime createdAt,
      String status,
      String gatewayReference,
      String responseCode,
      String refundReference) {}

  public record Result(
      boolean valid, String bookingId, String title, String message, boolean waiting) {}

  public List<Attempt> attempts(String bookingId) {
    return repo.jdbc()
        .query(
            "SELECT * FROM payment_attempt WHERE reservation_id=? ORDER BY created_at DESC,id DESC",
            (r, n) ->
                new Attempt(
                    r.getString("id"),
                    r.getString("reservation_id"),
                    r.getLong("amount"),
                    r.getObject("created_at", LocalDateTime.class),
                    r.getString("status"),
                    r.getString("gateway_reference"),
                    r.getString("response_code"),
                    r.getString("refund_reference")),
            bookingId);
  }

  private Optional<Attempt> attempt(String id) {
    var bookingIds =
        repo.jdbc()
            .queryForList(
                "SELECT reservation_id FROM payment_attempt WHERE id=?", String.class, id);
    return bookingIds.isEmpty()
        ? Optional.empty()
        : attempts(bookingIds.getFirst()).stream().filter(a -> a.id().equals(id)).findFirst();
  }

  public String start(String id, Account actor, String ip) {
    repo.lockSchedule();
    repo.expire(bookings.now());
    Booking booking = bookings.accessible(id, actor);
    BookingService.require(booking.userId() == actor.id(), "Chỉ chủ lượt đặt được thanh toán.");
    BookingService.require(
        booking.status() == BookingStatus.PENDING
            && booking.paymentStatus() == PaymentStatus.UNPAID,
        "Lượt đặt đã thanh toán hoặc hết hạn giữ bàn.");
    BookingService.require(
        gateway.configured(), "Cổng thanh toán chưa sẵn sàng. Vui lòng liên hệ quán.");
    // Repeated clicks share one pending transaction; a verified failure permits a new attempt.
    Attempt pending =
        attempts(id).stream().filter(a -> a.status().equals("PENDING")).findFirst().orElse(null);
    if (pending == null) {
      pending =
          new Attempt(
              UUID.randomUUID().toString().replace("-", ""),
              id,
              booking.deposit(),
              bookings.now(),
              "PENDING",
              null,
              null,
              null);
      repo.jdbc()
          .update(
              "INSERT INTO payment_attempt(id,reservation_id,amount,created_at,status)"
                  + " VALUES(?,?,?,?,?)",
              pending.id(),
              id,
              pending.amount(),
              pending.createdAt(),
              pending.status());
      repo.audit(id, actor.email(), "Bắt đầu thanh toán VNPAY Sandbox", bookings.now());
    }
    return gateway.paymentUrl(booking, pending.id(), pending.createdAt(), ip);
  }

  public String ipn(Map<String, String> parameters) {
    if (!gateway.verified(parameters)) return "97";
    VnpayGateway.Callback callback;
    try {
      callback = gateway.callback(parameters);
    } catch (IllegalArgumentException ex) {
      return ex.getMessage().equals("Invalid amount") ? "04" : "99";
    }
    repo.lockSchedule();
    repo.expire(bookings.now());
    var found = attempt(callback.reference());
    if (found.isEmpty()) return "01";
    Attempt attempt = found.get();
    if (attempt.amount() != callback.amount()) return "04";
    if (List.of("SUCCEEDED", "EXTRA_REFUND_PENDING", "REFUNDED").contains(attempt.status()))
      return "02";
    if (attempt.status().equals("FAILED") && !callback.success()) return "02";
    String status = "FAILED";
    if (callback.success()) {
      int duplicate =
          repo.jdbc()
              .queryForObject(
                  "SELECT COUNT(*) FROM payment_attempt WHERE gateway_reference=? AND status IN"
                      + " ('SUCCEEDED','EXTRA_REFUND_PENDING','REFUNDED') AND id<>?",
                  Integer.class,
                  callback.transaction(),
                  attempt.id());
      if (duplicate > 0) return "99";
      String acknowledged =
          bookings.gatewayPayment(
              attempt.bookingId(), callback.amount(), callback.transaction(), true);
      if (!acknowledged.equals("00") && !acknowledged.equals("02")) return acknowledged;
      status = acknowledged.equals("00") ? "SUCCEEDED" : "EXTRA_REFUND_PENDING";
      if (status.equals("EXTRA_REFUND_PENDING"))
        repo.audit(
            attempt.bookingId(),
            "VNPAY",
            "Nhận thêm một khoản cọc; cần hoàn giao dịch " + callback.transaction(),
            bookings.now());
    } else {
      repo.audit(
          attempt.bookingId(),
          "VNPAY",
          "Thanh toán chưa thành công (" + callback.responseCode() + ")",
          bookings.now());
    }
    repo.jdbc()
        .update(
            "UPDATE payment_attempt SET status=?,gateway_reference=?,response_code=? WHERE id=?",
            status,
            callback.transaction(),
            callback.responseCode(),
            attempt.id());
    return "00";
  }

  public Result browserReturn(Map<String, String> parameters) {
    if (!gateway.verified(parameters)) return invalid();
    try {
      var callback = gateway.callback(parameters);
      var found = attempt(callback.reference());
      if (found.isEmpty() || found.get().amount() != callback.amount()) return invalid();
      var result = status(found.get().bookingId());
      if (!result.waiting()) return result;
      if (!callback.success())
        return new Result(
            true,
            result.bookingId(),
            "Thanh toán chưa hoàn tất",
            gateway.failureMessage(callback.responseCode())
                + " Mở lượt đặt để kiểm tra trước khi thanh toán lại.",
            false);
      return result;
    } catch (IllegalArgumentException e) {
      return invalid();
    }
  }

  public Result statusFor(String id, Account actor) {
    bookings.accessible(id, actor);
    return status(id);
  }

  private Result status(String id) {
    var booking = repo.booking(id).orElseThrow();
    if (booking.paymentStatus() == PaymentStatus.REFUND_PENDING)
      return new Result(
          true,
          id,
          "Khoản cọc đang chờ hoàn",
          "GiaViên đã ghi nhận tiền nhưng lượt đặt không còn hiệu lực. Nhân viên sẽ liên hệ để hoàn"
              + " cọc.",
          false);
    if (booking.paymentStatus() != PaymentStatus.UNPAID)
      return new Result(
          true,
          id,
          "Đã ghi nhận thanh toán",
          "Trạng thái cọc: " + booking.paymentStatus().label + ". Mở lượt đặt để xem chi tiết.",
          false);
    if (booking.status() != BookingStatus.PENDING || !bookings.now().isBefore(booking.holdUntil()))
      return new Result(
          true,
          id,
          "Lượt giữ bàn đã kết thúc",
          "Nếu bạn đã thanh toán, GiaViên sẽ đối soát và xử lý hoàn cọc khi nhận xác nhận từ"
              + " VNPAY.",
          false);
    var attempts = attempts(id);
    if (!attempts.isEmpty()
        && attempts.stream().noneMatch(a -> a.status().equals("PENDING"))
        && attempts.getFirst().status().equals("FAILED"))
      return new Result(
          true,
          id,
          "Thanh toán chưa thành công",
          gateway.failureMessage(attempts.getFirst().responseCode()),
          false);
    return new Result(
        true,
        id,
        "Đang chờ xác nhận thanh toán",
        "GiaViên đang chờ VNPAY xác nhận. Bạn chưa cần thanh toán lại; trang này sẽ tự cập nhật.",
        true);
  }

  private Result invalid() {
    return new Result(
        false,
        null,
        "Chưa xác minh được kết quả",
        "Dữ liệu thanh toán không hợp lệ. Vui lòng mở lịch hẹn để kiểm tra hoặc liên hệ quán.",
        false);
  }

  public void recordExtraRefund(
      String bookingId, String attemptId, Account actor, String reference) {
    BookingService.require(bookings.staff(actor), "Chức năng dành cho nhân viên.");
    repo.lockSchedule();
    Attempt payment =
        attempt(attemptId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy giao dịch."));
    BookingService.require(
        payment.bookingId().equals(bookingId) && payment.status().equals("EXTRA_REFUND_PENDING"),
        "Giao dịch không cần hoàn thêm.");
    String checked = BookingService.checkedText(reference, 3, 150, "Mã hoàn tiền");
    repo.jdbc()
        .update(
            "UPDATE payment_attempt SET status='REFUNDED',refund_reference=? WHERE id=?",
            checked,
            attemptId);
    repo.audit(bookingId, actor.email(), "Đã hoàn khoản cọc thu thêm: " + checked, bookings.now());
  }
}
