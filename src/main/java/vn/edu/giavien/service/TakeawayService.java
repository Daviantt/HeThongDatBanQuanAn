package vn.edu.giavien.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.giavien.data.RestaurantRepository;
import vn.edu.giavien.domain.Models.*;


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

  public record Input(String customerName, String phone, LocalDateTime pickupAt, String paymentMethod,
                      Map<Long, Integer> items, Map<Long, Long> expectedUnitPrices) {}

  public record Quote(List<OrderLine> lines, long total) {}

  public static class PriceChangedException extends IllegalArgumentException {
    private final Quote quote;
    public PriceChangedException(Quote quote) {
      super("Giá món đã thay đổi. Vui lòng kiểm tra giá mới và xác nhận lại.");
      this.quote = quote;
    }
    public Quote quote() { return quote; }
  }

  public Quote quote(Map<Long, Integer> items) {
    if (items == null || items.isEmpty() || items.size() > 30)
      throw new IllegalArgumentException("Giỏ hàng chưa có món hợp lệ.");
    Map<Long, Dish> dishes = new HashMap<>();
    repo.dishes().forEach(d -> dishes.put(d.id(), d));
    List<OrderLine> lines = new ArrayList<>();
    for (var item : items.entrySet()) {
      Dish dish = dishes.get(item.getKey());
      int quantity = item.getValue() == null ? 0 : item.getValue();
      if (dish == null || !dish.available()) throw new IllegalArgumentException("Một món trong giỏ đã tạm hết. Vui lòng bỏ món đó hoặc quay lại thực đơn.");
      if (quantity < 1 || quantity > 20) throw new IllegalArgumentException("Số lượng mỗi món từ 1 đến 20.");
      lines.add(new OrderLine(dish.id(), dish.name(), dish.price(), quantity));
    }
    return new Quote(List.copyOf(lines), lines.stream().mapToLong(OrderLine::subtotal).sum());
  }

  public TakeawayOrder accessible(String id, Account actor) {
    expireUnpaid(id);
    TakeawayOrder order = repo.takeaway(id).orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn mang về."));
    if (order.userId() != actor.id() && !bookings.staff(actor))
      throw new AccessDeniedException("Bạn không có quyền xem đơn này.");
    return order;
  }

  public String create(Account actor, Input input) {
    bookings.requireCustomer(actor);
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
    if (method.equals("TRANSFER") && !demoEnabled())
      throw new IllegalArgumentException("Chuyển khoản demo hiện không khả dụng. Vui lòng chọn tiền mặt.");
    // Use the same lock as menu edits: a price cannot change between validation and insert.
    repo.lockSchedule();
    Quote quote = quote(input.items());
    if (input.expectedUnitPrices() == null)
      throw new IllegalArgumentException("Vui lòng cập nhật giá món trước khi xác nhận.");
    if (!input.expectedUnitPrices().keySet().equals(input.items().keySet())
        || quote.lines().stream().anyMatch(line -> !Objects.equals(
            input.expectedUnitPrices().get(line.menuItemId()), line.unitPrice())))
      throw new PriceChangedException(quote);
    String id = UUID.randomUUID().toString();
    repo.insertTakeaway(new TakeawayOrder(id, actor.id(), name, phone, input.pickupAt(), quote.total(), method,
        method.equals("CASH") ? TakeawayStatus.WAITING_PICKUP : TakeawayStatus.AWAITING_PAYMENT,
        now, null, null, null, quote.lines()));
    if (method.equals("CASH")) deductStock(quote.lines());
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
    bookings.requireCustomer(actor);
    if (!demoEnabled()) throw new IllegalArgumentException("Thanh toán QR demo hiện không khả dụng.");
    lockOrder(id);
    TakeawayOrder order = accessible(id, actor);
    if (order.userId() != actor.id()) throw new AccessDeniedException("Chỉ chủ đơn được mở thanh toán.");
    if (!order.paymentMethod().equals("TRANSFER") || order.status() != TakeawayStatus.AWAITING_PAYMENT
        || !bookings.now().isBefore(order.pickupAt()))
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
    expireUnpaid(session.orderId());
    TakeawayOrder order = repo.takeaway(session.orderId()).orElseThrow();
    if (order.paidAt() != null && Set.of(TakeawayStatus.WAITING_PICKUP, TakeawayStatus.PREPARING,
        TakeawayStatus.READY, TakeawayStatus.COMPLETED).contains(order.status()))
      return new PaymentState(order.id(), order.code(), order.total(), "PAID", 0, "Đã mô phỏng chuyển khoản thành công. Trạng thái đơn: " + order.status().label + ".");
    if (order.status() != TakeawayStatus.AWAITING_PAYMENT)
      return new PaymentState(order.id(), order.code(), order.total(), "CLOSED", 0, "Đơn không còn chờ thanh toán chuyển khoản.");
    long seconds = Math.max(0, (Duration.between(bookings.now(), session.createdAt().plusSeconds(60)).toMillis() + 999) / 1000);
    return new PaymentState(order.id(), order.code(), order.total(), seconds == 0 ? "READY" : "WAITING", seconds,
        seconds == 0 ? "Bạn có thể xác nhận chuyển khoản demo." : "Đợi đủ 1 phút để xác nhận chuyển khoản demo.");
  }

  public void confirmDemoPayment(String token) {
    lockOrder(session(token).orderId());
    PaymentState state = paymentStatus(token);
    if (state.status().equals("PAID")) return;
    if (!state.status().equals("READY")) throw new IllegalArgumentException(state.message());
    int changed = repo.jdbc().update(
        "UPDATE takeaway_order SET status='WAITING_PICKUP',paid_at=?,payment_reference=? WHERE id=? AND status='AWAITING_PAYMENT' AND pickup_at>?",
        bookings.now(), "DEMO-TL-" + token, state.orderId(), bookings.now());
    if (changed == 0 && repo.takeaway(state.orderId()).orElseThrow().status() != TakeawayStatus.WAITING_PICKUP)
      throw new IllegalArgumentException("Đơn không còn chờ thanh toán chuyển khoản.");
    if (changed == 1) deductStock(repo.takeawayLines(state.orderId()));
  }

  private void deductStock(List<OrderLine> lines) {
    // Lock dishes in a consistent order across orders. A shortage rolls back both
    // order creation/payment and every stock change in the surrounding transaction.
    for (OrderLine line : lines.stream().sorted(Comparator.comparingLong(OrderLine::menuItemId)).toList()) {
      int changed = repo.jdbc().update(
          "UPDATE menu_item SET stock_quantity=stock_quantity-? WHERE id=?"
              + " AND (stock_quantity IS NULL OR stock_quantity>=?)",
          line.quantity(), line.menuItemId(), line.quantity());
      BookingService.require(changed == 1,
          "Món " + line.itemName() + " không đủ số phần tồn để xác nhận đơn. Vui lòng liên hệ nhà hàng.");
    }
  }

  @Scheduled(fixedDelay = 30000, initialDelay = 30000)
  public void expireUnpaid() {
    repo.jdbc().update("UPDATE takeaway_order SET status='EXPIRED' WHERE status='AWAITING_PAYMENT' AND pickup_at<=?", bookings.now());
  }

  private void expireUnpaid(String id) {
    repo.jdbc().update("UPDATE takeaway_order SET status='EXPIRED' WHERE id=? AND status='AWAITING_PAYMENT' AND pickup_at<=?", id, bookings.now());
  }

  private void lockOrder(String id) {
    if (repo.jdbc().queryForList("SELECT id FROM takeaway_order WHERE id=? FOR UPDATE", String.class, id).isEmpty())
      throw new IllegalArgumentException("Không tìm thấy đơn mang về.");
  }

  public void staffAction(String id, Account actor, String action, String reference) {
    if (!bookings.staff(actor)) throw new AccessDeniedException("Chức năng dành cho nhân viên.");
    lockOrder(id);
    TakeawayOrder order = accessible(id, actor);
    TakeawayStatus next;
    switch (action) {
      case "prepare" -> {
        BookingService.require(order.status() == TakeawayStatus.WAITING_PICKUP, "Đơn chưa sẵn sàng để chuẩn bị.");
        next = TakeawayStatus.PREPARING;
      }
      case "ready" -> {
        BookingService.require(order.status() == TakeawayStatus.PREPARING, "Đơn phải đang được chuẩn bị.");
        next = TakeawayStatus.READY;
      }
      case "complete" -> {
        BookingService.require(order.status() == TakeawayStatus.READY, "Món phải sẵn sàng trước khi giao.");
        if (order.paymentMethod().equals("CASH") && order.paidAt() == null) {
          repo.jdbc().update("UPDATE takeaway_order SET paid_at=?,payment_reference=? WHERE id=?",
              bookings.now(), "CASH-" + id, id);
        }
        next = TakeawayStatus.COMPLETED;
        repo.jdbc().update("UPDATE takeaway_order SET completed_at=? WHERE id=?", bookings.now(), id);
      }
      case "cancel" -> {
        BookingService.require(Set.of(TakeawayStatus.AWAITING_PAYMENT, TakeawayStatus.WAITING_PICKUP,
            TakeawayStatus.PREPARING, TakeawayStatus.READY).contains(order.status()), "Không thể hủy đơn ở trạng thái này.");
        next = order.paidAt() == null ? TakeawayStatus.CANCELLED : TakeawayStatus.REFUND_PENDING;
      }
      case "refund" -> {
        BookingService.require(order.status() == TakeawayStatus.REFUND_PENDING, "Đơn không chờ hoàn tiền.");
        repo.jdbc().update("UPDATE takeaway_order SET refund_reference=? WHERE id=?",
            text(reference, 3, 150, "Mã xác nhận hoàn tiền demo"), id);
        next = TakeawayStatus.CANCELLED;
      }
      default -> throw new IllegalArgumentException("Thao tác không hợp lệ.");
    }
    repo.jdbc().update("UPDATE takeaway_order SET status=? WHERE id=?", next.name(), id);
  }

  private boolean demoEnabled() {
    return demoPayments.enabled();
  }
}
