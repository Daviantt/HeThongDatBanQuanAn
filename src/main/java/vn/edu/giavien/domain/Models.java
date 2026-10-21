package vn.edu.giavien.domain;

import java.time.LocalDateTime;
import java.util.List;

public final class Models {
  private Models() {}

  public record Account(
      long id, String email, String passwordHash, String fullName, String phone, String role) {}

  public record DiningTable(
      long id, String code, String zone, int mapX, int mapY, boolean active, int floor) {}

  public record Dish(
      long id,
      String name,
      String description,
      String category,
      long price,
      boolean available,
      String illustration) {}

  public record Settings(
      long depositPerTable,
      int holdMinutes,
      int cleanupMinutes,
      int openHour,
      int closeHour,
      int graceMinutes) {}

  public record OrderLine(long menuItemId, String itemName, long unitPrice, int quantity) {
    public long subtotal() {
      return unitPrice * quantity;
    }
  }

  public enum TakeawayStatus {
    AWAITING_PAYMENT("Chờ chuyển khoản"),
    WAITING_PICKUP("Chờ khách đến lấy"),
    COMPLETED("Đã giao món"),
    CANCELLED("Đã hủy");
    public final String label;

    TakeawayStatus(String label) {
      this.label = label;
    }
  }

  /** A food order collected at the restaurant; it deliberately has no dining-table allocation. */
  public record TakeawayOrder(
      String id,
      long userId,
      String customerName,
      String customerPhone,
      LocalDateTime pickupAt,
      long total,
      String paymentMethod,
      TakeawayStatus status,
      LocalDateTime createdAt,
      LocalDateTime paidAt,
      String paymentReference,
      List<OrderLine> lines) {
    public String code() {
      return "TL-" + id.substring(0, 8).toUpperCase();
    }
  }

  public enum BookingStatus {
    PENDING("Chờ đặt cọc"),
    CONFIRMED("Đã xác nhận"),
    SEATED("Đang phục vụ"),
    COMPLETED("Hoàn tất"),
    CANCELLED("Đã hủy"),
    EXPIRED("Hết hạn giữ bàn"),
    NO_SHOW("Khách không đến");
    public final String label;

    BookingStatus(String label) {
      this.label = label;
    }

    public String getLabel() {
      return label;
    }
  }

  public enum PaymentStatus {
    UNPAID("Chưa thanh toán"),
    PAID("Đã nhận cọc"),
    REFUND_PENDING("Chờ hoàn cọc"),
    REFUNDED("Đã hoàn cọc"),
    FORFEITED("Không hoàn cọc");
    public final String label;

    PaymentStatus(String label) {
      this.label = label;
    }

    public String getLabel() {
      return label;
    }
  }

  public record Booking(
      String id,
      long userId,
      String customerName,
      String customerEmail,
      String customerPhone,
      LocalDateTime startAt,
      LocalDateTime endAt,
      int guests,
      String notes,
      BookingStatus status,
      PaymentStatus paymentStatus,
      long deposit,
      long foodDeposit,
      LocalDateTime createdAt,
      LocalDateTime holdUntil,
      LocalDateTime paidAt,
      LocalDateTime releasedAt,
      List<Long> tableIds,
      List<String> tableCodes,
      List<OrderLine> lines) {
    public String code() {
      return "GV-" + id.substring(0, 8).toUpperCase();
    }

    public String tablesLabel() {
      return String.join(" + ", tableCodes);
    }

    public long foodTotal() {
      return lines.stream().mapToLong(OrderLine::subtotal).sum();
    }

    public long tableDeposit() {
      return deposit - foodDeposit;
    }
  }

  public record ChangeRequest(
      long id,
      String reservationId,
      String message,
      LocalDateTime createdAt,
      String status,
      String response) {}

  public record AuditEvent(String actor, String action, LocalDateTime createdAt) {}
}
