package vn.edu.moc.service;

import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.moc.data.RestaurantRepository;
import vn.edu.moc.domain.BookingPolicy;
import vn.edu.moc.domain.Models.*;

@Service
@Transactional
public class BookingService {
  private final RestaurantRepository repo;
  private final Clock clock;
  private final PasswordEncoder passwords;

  public BookingService(RestaurantRepository repo, Clock clock, PasswordEncoder passwords) {
    this.repo = repo;
    this.clock = clock;
    this.passwords = passwords;
  }

  public LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  public Account account(String email) {
    return repo.account(email).orElseThrow(() -> new AccessDeniedException("Vui lòng đăng nhập."));
  }

  public boolean staff(Account account) {
    return Set.of("STAFF", "ADMIN").contains(account.role());
  }

  public Booking accessible(String id, Account actor) {
    Booking booking =
        repo.booking(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lượt đặt bàn."));
    if (booking.userId() != actor.id() && !staff(actor))
      throw new AccessDeniedException("Bạn không có quyền xem lượt đặt này.");
    return booking;
  }

  private void owner(Booking booking, Account actor) {
    if (booking.userId() != actor.id())
      throw new AccessDeniedException("Chỉ chủ lượt đặt được thực hiện thao tác này.");
  }

  private void requireStaff(Account actor) {
    if (!staff(actor)) throw new AccessDeniedException("Chức năng dành cho nhân viên.");
  }

  private void requireAdmin(Account actor) {
    if (!actor.role().equals("ADMIN"))
      throw new AccessDeniedException("Chức năng dành cho quản lý.");
  }

  public record BookingInput(
      LocalDateTime startAt,
      LocalDateTime endAt,
      int guests,
      List<Long> tableIds,
      Map<Long, Integer> items,
      String notes) {}

  public record TableOption(List<Long> tableIds, String label, int capacity, long deposit) {}

  public record Availability(
      List<DiningTable> tables,
      List<Long> unavailable,
      List<TableOption> options,
      Settings settings) {}

  public void validateTime(LocalDateTime start, LocalDateTime end, int guests) {
    require(start != null && end != null, "Vui lòng chọn giờ đến và giờ kết thúc.");
    require(guests >= 1 && guests <= 12, "Mỗi lượt đặt dành cho 1–12 khách.");
    require(start.isAfter(now()), "Giờ đến phải nằm trong tương lai.");
    require(
        start.toLocalDate().equals(end.toLocalDate()), "Giờ đến và giờ kết thúc phải cùng ngày.");
    require(end.isAfter(start), "Giờ kết thúc phải sau giờ đến.");
    require(
        !start.toLocalDate().isAfter(now().toLocalDate().plusDays(60)),
        "Bạn có thể đặt trước tối đa 60 ngày.");
    Settings settings = repo.settings();
    require(
        !start.toLocalTime().isBefore(LocalTime.of(settings.openHour(), 0))
            && !end.toLocalTime().isAfter(LocalTime.of(settings.closeHour(), 0)),
        "Giờ phục vụ: %02d:00–%02d:00.".formatted(settings.openHour(), settings.closeHour()));
    require(
        Duration.between(start, end).toMinutes() >= 30, "Thời gian dùng bàn tối thiểu 30 phút.");
    require(
        start.getSecond() == 0
            && end.getSecond() == 0
            && start.getNano() == 0
            && end.getNano() == 0,
        "Vui lòng chọn thời gian chính xác đến phút.");
  }

  public Availability availability(LocalDateTime start, LocalDateTime end, int guests) {
    validateTime(start, end, guests);
    Settings settings = repo.settings();
    Set<Long> busy = busyTables(start, end, null);
    List<DiningTable> tables = repo.tables();
    tables.stream().filter(t -> !t.active()).forEach(t -> busy.add(t.id()));
    int required = BookingPolicy.requiredTables(guests);
    List<List<Long>> sets = new ArrayList<>();
    if (required == 1) tables.forEach(t -> sets.add(List.of(t.id())));
    else sets.addAll(repo.combinations());
    List<TableOption> options =
        sets.stream()
            .filter(ids -> ids.size() == required && Collections.disjoint(ids, busy))
            .map(
                ids ->
                    new TableOption(
                        ids,
                        String.join(
                            " + ",
                            ids.stream()
                                .map(
                                    id ->
                                        tables.stream()
                                            .filter(t -> t.id() == id)
                                            .findFirst()
                                            .orElseThrow()
                                            .code())
                                .toList()),
                        ids.size() * 4,
                        ids.size() * settings.depositPerTable()))
            .toList();
    return new Availability(tables, busy.stream().sorted().toList(), options, settings);
  }

  private Set<Long> busyTables(LocalDateTime start, LocalDateTime end, String exclude) {
    Set<Long> busy = new HashSet<>();
    for (Booking other : repo.activeBookings(now())) {
      if (other.id().equals(exclude)) continue;
      LocalDateTime occupiedUntil =
          other.status() == BookingStatus.COMPLETED ? other.releasedAt() : other.endAt();
      if (other.status() == BookingStatus.SEATED && occupiedUntil.isBefore(now()))
        occupiedUntil = now();
      if (BookingPolicy.overlaps(
          start, end, other.startAt(), occupiedUntil, repo.settings().cleanupMinutes()))
        busy.addAll(other.tableIds());
    }
    return busy;
  }

  private List<Long> validateTables(
      List<Long> input, int guests, LocalDateTime start, LocalDateTime end, String exclude) {
    require(
        input != null
            && !input.isEmpty()
            && input.size() <= 3
            && input.stream().noneMatch(Objects::isNull),
        "Vui lòng chọn bàn phù hợp.");
    List<Long> ids = input.stream().distinct().sorted().toList();
    require(
        ids.size() == input.size() && ids.size() == BookingPolicy.requiredTables(guests),
        "Số bàn chưa phù hợp với số khách (4 người/bàn).");
    Set<Long> active =
        new HashSet<>(
            repo.tables().stream().filter(DiningTable::active).map(DiningTable::id).toList());
    require(active.containsAll(ids), "Bàn đã chọn không còn phục vụ.");
    require(
        ids.size() == 1 || repo.combinations().contains(ids),
        "Các bàn này không thuộc một tổ hợp được phép ghép.");
    require(
        Collections.disjoint(ids, busyTables(start, end, exclude)),
        "Bàn vừa được đặt hoặc đang được giữ. Vui lòng chọn bàn khác.");
    return ids;
  }

  private List<OrderLine> makeLines(Map<Long, Integer> input, List<OrderLine> previous) {
    if (input == null) return List.of();
    require(input.size() <= 100, "Danh sách món quá dài.");
    Map<Long, Dish> dishes = new HashMap<>();
    repo.dishes().forEach(d -> dishes.put(d.id(), d));
    Map<Long, OrderLine> existing = new HashMap<>();
    previous.forEach(l -> existing.put(l.menuItemId(), l));
    List<OrderLine> result = new ArrayList<>();
    for (var entry : input.entrySet()) {
      require(
          entry.getValue() != null && entry.getValue() >= 0 && entry.getValue() <= 20,
          "Số lượng mỗi món từ 0 đến 20.");
      if (entry.getValue() == 0) continue;
      Dish dish = dishes.get(entry.getKey());
      OrderLine old = existing.get(entry.getKey());
      require(dish != null, "Món ăn không tồn tại.");
      require(
          dish.available() || (old != null && entry.getValue() <= old.quantity()),
          "Món " + dish.name() + " đang tạm hết.");
      result.add(
          new OrderLine(
              dish.id(),
              old == null ? dish.name() : old.itemName(),
              old == null ? dish.price() : old.unitPrice(),
              entry.getValue()));
    }
    return result;
  }

  public String create(Account actor, BookingInput input) {
    require(input != null, "Dữ liệu đặt bàn không hợp lệ.");
    repo.lockSchedule();
    repo.expire(now());
    validateTime(input.startAt(), input.endAt(), input.guests());
    List<Long> ids =
        validateTables(input.tableIds(), input.guests(), input.startAt(), input.endAt(), null);
    String notes = checkedText(input.notes(), 0, 500, "Ghi chú");
    List<OrderLine> lines = makeLines(input.items(), List.of());
    String id = UUID.randomUUID().toString();
    Settings settings = repo.settings();
    LocalDateTime holdUntil = now().plusMinutes(settings.holdMinutes());
    if (holdUntil.isAfter(input.startAt())) holdUntil = input.startAt();
    repo.insertBooking(
        id,
        actor.id(),
        input.startAt(),
        input.endAt(),
        input.guests(),
        notes,
        ids.size() * settings.depositPerTable(),
        now(),
        holdUntil,
        ids);
    repo.replaceLines(id, lines);
    repo.audit(id, actor.email(), "Tạo lượt đặt; giữ bàn chờ cọc", now());
    return id;
  }

  public void editItems(String id, Account actor, Map<Long, Integer> items, String notes) {
    repo.lockSchedule();
    repo.expire(now());
    Booking b = accessible(id, actor);
    owner(b, actor);
    require(
        BookingPolicy.editable(b, now()),
        "Chỉ được sửa món và ghi chú trước giờ đến ít nhất 2 tiếng.");
    repo.replaceLines(id, makeLines(items, b.lines()));
    repo.jdbc()
        .update(
            "UPDATE reservation SET notes=? WHERE id=?", checkedText(notes, 0, 500, "Ghi chú"), id);
    repo.audit(id, actor.email(), "Cập nhật món đặt trước và ghi chú", now());
  }

  public void cancel(String id, Account actor, boolean byRestaurant) {
    repo.lockSchedule();
    repo.expire(now());
    Booking b = accessible(id, actor);
    if (byRestaurant) {
      requireStaff(actor);
      require(
          b.status() == BookingStatus.PENDING || b.status() == BookingStatus.CONFIRMED,
          "Lượt đặt không thể hủy ở trạng thái này.");
    } else {
      owner(b, actor);
      require(BookingPolicy.cancellable(b, now()), "Lượt đặt không còn được phép tự hủy.");
    }
    PaymentStatus status = b.paymentStatus();
    if (status == PaymentStatus.PAID)
      status =
          (byRestaurant || BookingPolicy.refundable(now(), b.startAt()))
              ? PaymentStatus.REFUND_PENDING
              : PaymentStatus.FORFEITED;
    repo.jdbc()
        .update(
            "UPDATE reservation SET status='CANCELLED',payment_status=? WHERE id=?",
            status.name(),
            id);
    repo.jdbc()
        .update(
            "UPDATE change_request SET status='CLOSED',response='Lượt đặt đã hủy' WHERE"
                + " reservation_id=? AND status='PENDING'",
            id);
    repo.audit(id, actor.email(), byRestaurant ? "Quán hủy lượt đặt" : "Khách hủy lượt đặt", now());
  }

  public void requestChange(String id, Account actor, String message) {
    repo.lockSchedule();
    repo.expire(now());
    Booking b = accessible(id, actor);
    owner(b, actor);
    require(
        b.status() == BookingStatus.CONFIRMED && now().isBefore(b.startAt()),
        "Chỉ gửi yêu cầu cho lượt đặt đã xác nhận và chưa đến giờ.");
    require(
        repo.requests(id).stream().noneMatch(r -> r.status().equals("PENDING")),
        "Bạn đã có một yêu cầu đang chờ xử lý.");
    repo.jdbc()
        .update(
            "INSERT INTO change_request(reservation_id,message,created_at,status)"
                + " VALUES(?,?,?,'PENDING')",
            id,
            checkedText(message, 5, 500, "Yêu cầu"),
            now());
    repo.audit(id, actor.email(), "Gửi yêu cầu đổi bàn / giờ", now());
  }

  public void reschedule(
      String id,
      Account actor,
      LocalDateTime start,
      LocalDateTime end,
      int guests,
      List<Long> tables,
      String response) {
    requireStaff(actor);
    repo.lockSchedule();
    repo.expire(now());
    Booking b = accessible(id, actor);
    require(
        b.status() == BookingStatus.CONFIRMED && now().isBefore(b.startAt()),
        "Chỉ đổi lượt đặt đã xác nhận và chưa đến giờ.");
    validateTime(start, end, guests);
    List<Long> ids = validateTables(tables, guests, start, end, id);
    require(
        ids.size() == b.tableIds().size(),
        "Bản đầu hỗ trợ đổi bàn/giờ giữ nguyên số bàn. Đổi số bàn cần một lượt đặt mới để đối soát"
            + " cọc.");
    repo.jdbc()
        .update(
            "UPDATE reservation SET start_at=?,end_at=?,guests=? WHERE id=?",
            start,
            end,
            guests,
            id);
    repo.replaceTables(id, ids);
    repo.jdbc()
        .update(
            "UPDATE change_request SET status='APPROVED',response=? WHERE reservation_id=? AND"
                + " status='PENDING'",
            checkedText(response, 1, 500, "Phản hồi"),
            id);
    repo.audit(id, actor.email(), "Đổi bàn / giờ theo yêu cầu khách", now());
  }

  public void rejectChange(String id, Account actor, String response) {
    requireStaff(actor);
    repo.lockSchedule();
    accessible(id, actor);
    require(
        repo.jdbc()
                .update(
                    "UPDATE change_request SET status='REJECTED',response=? WHERE reservation_id=?"
                        + " AND status='PENDING'",
                    checkedText(response, 1, 500, "Phản hồi"),
                    id)
            > 0,
        "Không có yêu cầu chờ xử lý.");
    repo.audit(id, actor.email(), "Từ chối yêu cầu đổi bàn / giờ", now());
  }

  public void staffAction(String id, Account actor, String action, String reference) {
    requireStaff(actor);
    repo.lockSchedule();
    repo.expire(now());
    Booking b = accessible(id, actor);
    switch (action) {
      case "checkin" -> {
        require(b.status() == BookingStatus.CONFIRMED, "Chỉ nhận khách cho lượt đặt đã xác nhận.");
        require(
            !now().isBefore(b.startAt().minusMinutes(15)) && now().isBefore(b.endAt()),
            "Nhận khách từ 15 phút trước giờ hẹn đến trước giờ kết thúc.");
        require(
            Collections.disjoint(b.tableIds(), busyTables(now(), b.endAt(), id)),
            "Bàn chưa sẵn sàng để nhận khách sớm.");
        repo.jdbc().update("UPDATE reservation SET status='SEATED' WHERE id=?", id);
      }
      case "complete" -> {
        require(b.status() == BookingStatus.SEATED, "Khách phải được nhận bàn trước khi hoàn tất.");
        repo.jdbc()
            .update(
                "UPDATE reservation SET status='COMPLETED',released_at=? WHERE id=?", now(), id);
      }
      case "noshow" -> {
        require(
            b.status() == BookingStatus.CONFIRMED
                && !now().isBefore(b.startAt().plusMinutes(repo.settings().graceMinutes())),
            "Chưa hết thời gian chờ khách hoặc lượt đặt không hợp lệ.");
        repo.jdbc()
            .update(
                "UPDATE reservation SET status='NO_SHOW',payment_status='FORFEITED' WHERE id=?",
                id);
      }
      case "refund" -> {
        require(
            b.paymentStatus() == PaymentStatus.REFUND_PENDING, "Không có khoản cọc đang chờ hoàn.");
        repo.jdbc()
            .update(
                "UPDATE reservation SET payment_status='REFUNDED',refund_reference=? WHERE id=?",
                checkedText(reference, 3, 150, "Mã giao dịch hoàn cọc"),
                id);
      }
      default -> throw new IllegalArgumentException("Thao tác không hợp lệ.");
    }
    repo.audit(
        id,
        actor.email(),
        switch (action) {
          case "checkin" -> "Khách đã đến; bắt đầu chuẩn bị món";
          case "complete" -> "Hoàn tất phục vụ";
          case "noshow" -> "Khách không đến";
          default -> "Ghi nhận đã hoàn cọc";
        },
        now());
  }

  public void demoPay(String id, Account actor) {
    repo.lockSchedule();
    repo.expire(now());
    Booking b = accessible(id, actor);
    owner(b, actor);
    require(
        b.status() == BookingStatus.PENDING && b.paymentStatus() == PaymentStatus.UNPAID,
        "Lượt đặt đã thanh toán hoặc hết hạn giữ bàn.");
    confirmPayment(id, "DEMO", "DEMO-" + UUID.randomUUID());
  }

  // Called only after authenticating and validating the gateway callback.
  public String gatewayPayment(String id, long amount, String reference, boolean success) {
    repo.lockSchedule();
    repo.expire(now());
    Optional<Booking> found = repo.booking(id);
    if (found.isEmpty()) return "01";
    Booking b = found.get();
    if (b.deposit() != amount) return "04";
    if (b.paymentStatus() != PaymentStatus.UNPAID) return "02";
    if (!success) {
      repo.audit(id, "VNPAY", "Thanh toán chưa thành công", now());
      return "00";
    }
    confirmPayment(id, "VNPAY", reference);
    return "00";
  }

  private void confirmPayment(String id, String provider, String reference) {
    Booking b = repo.booking(id).orElseThrow();
    boolean valid = b.status() == BookingStatus.PENDING && now().isBefore(b.holdUntil());
    repo.jdbc()
        .update(
            "UPDATE reservation SET"
                + " status=?,payment_status=?,paid_at=?,payment_provider=?,payment_reference=?"
                + " WHERE id=?",
            valid ? "CONFIRMED" : b.status().name(),
            valid ? "PAID" : "REFUND_PENDING",
            now(),
            provider,
            reference,
            id);
    repo.audit(
        id,
        provider,
        valid
            ? "Nhận cọc; xác nhận đặt bàn"
            : "Nhận tiền sau khi lượt đặt hết hiệu lực; chờ hoàn cọc",
        now());
  }

  @Scheduled(fixedDelay = 30000, initialDelay = 30000)
  public void expireHolds() {
    repo.lockSchedule();
    repo.expire(now());
  }

  public void register(String email, String password, String fullName, String phone) {
    email = checkedText(email, 3, 180, "Email").toLowerCase(Locale.ROOT);
    require(email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"), "Email chưa đúng định dạng.");
    require(
        password != null
            && password.length() >= 8
            && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72,
        "Mật khẩu cần ít nhất 8 ký tự và tối đa 72 byte.");
    phone = checkedText(phone, 9, 15, "Số điện thoại");
    require(phone.matches("\\+?[0-9]{9,14}"), "Số điện thoại chưa hợp lệ.");
    try {
      repo.insertAccount(
          email,
          passwords.encode(password),
          checkedText(fullName, 2, 100, "Họ tên"),
          phone,
          "CUSTOMER");
    } catch (DuplicateKeyException ex) {
      throw new IllegalArgumentException("Email này đã được đăng ký.");
    }
  }

  public void updateSettings(Account actor, long deposit, int hold, int cleanup, int grace) {
    requireAdmin(actor);
    repo.lockSchedule();
    require(deposit >= 10000 && deposit <= 10000000, "Cọc mỗi bàn từ 10.000đ đến 10.000.000đ.");
    require(
        hold >= 5 && hold <= 30 && cleanup >= 0 && cleanup <= 60 && grace >= 0 && grace <= 60,
        "Thông số thời gian không hợp lệ.");
    repo.jdbc()
        .update(
            "UPDATE restaurant_settings SET"
                + " deposit_per_table=?,hold_minutes=?,cleanup_minutes=?,grace_minutes=? WHERE"
                + " id=1",
            deposit,
            hold,
            cleanup,
            grace);
  }

  public void createStaff(Account actor, String email, String password, String name, String phone) {
    requireAdmin(actor);
    register(email, password, name, phone);
    repo.jdbc()
        .update(
            "UPDATE app_user SET role='STAFF' WHERE email=?",
            email.strip().toLowerCase(Locale.ROOT));
  }

  public void saveDish(
      Account actor,
      long id,
      String name,
      String description,
      String category,
      long price,
      boolean available) {
    requireAdmin(actor);
    repo.lockSchedule();
    require(price >= 1000 && price <= 10000000, "Giá món không hợp lệ.");
    name = checkedText(name, 2, 100, "Tên món");
    description = checkedText(description, 0, 400, "Mô tả");
    require(
        Set.of("Khai vị", "Món chính", "Tráng miệng", "Đồ uống").contains(category),
        "Danh mục không hợp lệ.");
    if (id == 0)
      repo.jdbc()
          .update(
              "INSERT INTO menu_item(name,description,category,price,available,illustration)"
                  + " VALUES(?,?,?,?,?,'rice')",
              name,
              description,
              category,
              price,
              available);
    else
      require(
          repo.jdbc()
                  .update(
                      "UPDATE menu_item SET name=?,description=?,category=?,price=?,available=?"
                          + " WHERE id=?",
                      name,
                      description,
                      category,
                      price,
                      available,
                      id)
              == 1,
          "Món không tồn tại.");
  }

  public void toggleTable(Account actor, long id) {
    requireAdmin(actor);
    repo.lockSchedule();
    require(
        repo.activeBookings(now()).stream()
            .noneMatch(b -> b.tableIds().contains(id) && b.endAt().isAfter(now())),
        "Bàn còn lượt đặt đang hiệu lực; hãy xử lý các lượt đặt trước.");
    require(
        repo.jdbc()
                .update(
                    "UPDATE dining_table SET active=NOT active WHERE id=? AND retired=FALSE", id)
            == 1,
        "Bàn không tồn tại.");
  }

  public void saveCombination(Account actor, String text, boolean remove) {
    requireAdmin(actor);
    repo.lockSchedule();
    List<Long> ids;
    try {
      ids = resolveTableNumbers(text).stream().distinct().sorted().toList();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Nhập mã số bàn cách nhau bằng dấu phẩy, ví dụ 1,2,3.");
    }
    require(ids.size() >= 2 && ids.size() <= 3, "Tổ hợp gồm 2 hoặc 3 bàn.");
    require(
        repo.tables().stream().map(DiningTable::id).toList().containsAll(ids),
        "Tổ hợp chứa bàn không tồn tại.");
    String canonical = String.join(",", ids.stream().map(String::valueOf).toList());
    require(
        remove
            || vn.edu.moc.domain.FloorPlan.canJoin(
                repo.tables().stream().filter(t -> ids.contains(t.id())).toList()),
        "Chỉ ghép các bàn liền nhau theo hàng ngang hoặc dọc, không đi qua khu vườn.");
    if (remove) repo.jdbc().update("DELETE FROM table_combination WHERE table_ids=?", canonical);
    else if (!repo.combinations().contains(ids))
      repo.jdbc().update("INSERT INTO table_combination(table_ids) VALUES(?)", canonical);
  }

  public List<Long> resolveTableNumbers(String text) {
    var tables = repo.tables();
    try {
      return Arrays.stream(text.split(","))
          .map(String::trim)
          .map(n -> "B%02d".formatted(Integer.parseInt(n)))
          .map(
              code ->
                  tables.stream()
                      .filter(t -> t.code().equals(code))
                      .findFirst()
                      .orElseThrow(() -> new IllegalArgumentException("Bàn không tồn tại."))
                      .id())
          .toList();
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Nhập mã số bàn cách nhau bằng dấu phẩy, ví dụ 1,4.");
    }
  }

  public static String checkedText(String value, int min, int max, String name) {
    String text = value == null ? "" : value.strip();
    require(
        text.length() >= min && text.length() <= max,
        name + " cần từ " + min + " đến " + max + " ký tự.");
    return text;
  }

  public static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }
}
