package vn.edu.giavien.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.giavien.data.RestaurantRepository;
import vn.edu.giavien.domain.Models.*;

/** Keeps takeaway orders separate from reservations, so a pickup never occupies a table. */
@Service
@Transactional
public class TakeawayService {
  private final RestaurantRepository repo;
  private final BookingService bookings;
  private final DemoPaymentService demoPayments;

  public TakeawayService(
      RestaurantRepository repo,
      BookingService bookings,
      DemoPaymentService demoPayments) {
    this.repo = repo;
    this.bookings = bookings;
    this.demoPayments = demoPayments;
  }

  public record Input(String customerName, String phone, LocalDateTime pickupAt, String paymentMethod, Map<Long, Integer> items) {}

  public TakeawayOrder accessible(String id, Account actor) {
    TakeawayOrder order = repo.takeaway(id).orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn mang về."));
    if (order.userId() != actor.id() && !bookings.staff(actor))
      throw new AccessDeniedException("Bạn không có quyền xem đơn này.");
    return order;
  }

  public String create(Account actor, Input input) {
    if (input == null) throw new IllegalArgumentException("Dữ liệu đơn mang về không hợp lệ.");
    String name = text(input.customerName(), 2, 100, "Tên người nhận");
    String phone = text(input.phone(), 8, 20, "Số điện thoại");
    if (!phone.matches("[0-9+ .()-]+")) throw new IllegalArgumentException("Số điện thoại không hợp lệ.");
    if (input.pickupAt() == null || input.pickupAt().getSecond() != 0 || input.pickupAt().getNano() != 0)
      throw new IllegalArgumentException("Vui lòng chọn giờ lấy chính xác đến phút.");
    LocalDateTime now = bookings.now();
    if (input.pickupAt().isBefore(now.plusMinutes(15)))
      throw new IllegalArgumentException("Giờ lấy cần cách thời điểm đặt ít nhất 15 phút.");
    if (!input.pickupAt().toLocalDate().equals(now.toLocalDate()))
      throw new IllegalArgumentException("Đơn mang về hiện chỉ nhận trong ngày hôm nay.");
    Settings settings = repo.settings();
    if (input.pickupAt().getHour() < settings.openHour() || input.pickupAt().getHour() >= settings.closeHour())
      throw new IllegalArgumentException("Giờ lấy món: %02d:00–%02d:00.".formatted(settings.openHour(), settings.closeHour()));
    String method = input.paymentMethod() == null ? "" : input.paymentMethod().strip().toUpperCase(Locale.ROOT);
    if (!Set.of("CASH", "TRANSFER").contains(method)) throw new IllegalArgumentException("Vui lòng chọn phương thức thanh toán.");
    if (input.items() == null || input.items().isEmpty() || input.items().size() > 30)
      throw new IllegalArgumentException("Giỏ hàng chưa có món hợp lệ.");
    Map<Long, Dish> dishes = new HashMap<>();
    repo.dishes().forEach(d -> dishes.put(d.id(), d));
    List<OrderLine> lines = new ArrayList<>();
    for (var item : input.items().entrySet()) {
      Dish dish = dishes.get(item.getKey());
      int quantity = item.getValue() == null ? 0 : item.getValue();
      if (dish == null || !dish.available()) throw new IllegalArgumentException("Một món trong giỏ đã tạm hết.");
      if (quantity < 1 || quantity > 20) throw new IllegalArgumentException("Số lượng mỗi món từ 1 đến 20.");
      lines.add(new OrderLine(dish.id(), dish.name(), dish.price(), quantity));
    }
    long total = lines.stream().mapToLong(OrderLine::subtotal).sum();
    String id = UUID.randomUUID().toString();
    repo.insertTakeaway(new TakeawayOrder(id, actor.id(), name, phone, input.pickupAt(), total, method,
        method.equals("CASH") ? TakeawayStatus.WAITING_PICKUP : TakeawayStatus.AWAITING_PAYMENT,
        now, null, null, lines));
    return id;
  }

  private static String text(String value, int min, int max, String label) {
    String cleaned = value == null ? "" : value.strip();
    if (cleaned.length() < min || cleaned.length() > max) throw new IllegalArgumentException(label + " phải có từ " + min + " đến " + max + " ký tự.");
    return cleaned;
  }

  public record PaymentSession(String token, String orderId, LocalDateTime createdAt) {}
  public record PaymentState(String orderId, String code, long amount, String status, long remainingSeconds, String message) {}

  public String startDemoPayment(String id, Account actor) {
    if (!demoEnabled()) throw new IllegalArgumentException("Thanh toán QR demo hiện không khả dụng.");
    TakeawayOrder order = accessible(id, actor);
    if (order.userId() != actor.id()) throw new AccessDeniedException("Chỉ chủ đơn được mở thanh toán.");
    if (!order.paymentMethod().equals("TRANSFER") || order.status() != TakeawayStatus.AWAITING_PAYMENT)
      throw new IllegalArgumentException("Đơn này không chờ thanh toán chuyển khoản.");
    var tokens = repo.jdbc().queryForList("SELECT token FROM takeaway_demo_payment WHERE order_id=?", String.class, id);
    if (!tokens.isEmpty()) return tokens.getFirst();
    String token = UUID.randomUUID().toString().replace("-", "");
    repo.jdbc().update("INSERT INTO takeaway_demo_payment(token,order_id,created_at) VALUES(?,?,?)", token, id, bookings.now());
    return token;
  }

  private PaymentSession session(String token) {
    if (!demoEnabled() || token == null || !token.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Phiên thanh toán không tồn tại.");
    return repo.jdbc().query("SELECT * FROM takeaway_demo_payment WHERE token=?", (r, n) ->
        new PaymentSession(r.getString("token"), r.getString("order_id"), r.getObject("created_at", LocalDateTime.class)), token)
        .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Phiên thanh toán không tồn tại."));
  }

  public PaymentState paymentStatus(String token) {
    PaymentSession session = session(token);
    TakeawayOrder order = repo.takeaway(session.orderId()).orElseThrow();
    if (order.status() == TakeawayStatus.WAITING_PICKUP)
      return new PaymentState(order.id(), order.code(), order.total(), "PAID", 0, "Đã mô phỏng chuyển khoản thành công. Quán sẽ chuẩn bị món cho bạn.");
    if (order.status() != TakeawayStatus.AWAITING_PAYMENT)
      return new PaymentState(order.id(), order.code(), order.total(), "CLOSED", 0, "Đơn không còn chờ thanh toán chuyển khoản.");
    long seconds = Math.max(0, (Duration.between(bookings.now(), session.createdAt().plusSeconds(60)).toMillis() + 999) / 1000);
    return new PaymentState(order.id(), order.code(), order.total(), seconds == 0 ? "READY" : "WAITING", seconds,
        seconds == 0 ? "Bạn có thể xác nhận chuyển khoản demo." : "Đợi đủ 1 phút để xác nhận chuyển khoản demo.");
  }

  public void confirmDemoPayment(String token) {
    PaymentState state = paymentStatus(token);
    if (state.status().equals("PAID")) return;
    if (!state.status().equals("READY")) throw new IllegalArgumentException(state.message());
    int changed = repo.jdbc().update(
        "UPDATE takeaway_order SET status='WAITING_PICKUP',paid_at=?,payment_reference=? WHERE id=? AND status='AWAITING_PAYMENT'",
        bookings.now(), "DEMO-TL-" + token, state.orderId());
    if (changed == 0 && repo.takeaway(state.orderId()).orElseThrow().status() != TakeawayStatus.WAITING_PICKUP)
      throw new IllegalArgumentException("Đơn không còn chờ thanh toán chuyển khoản.");
  }

  private boolean demoEnabled() {
    return demoPayments.enabled();
  }
}
