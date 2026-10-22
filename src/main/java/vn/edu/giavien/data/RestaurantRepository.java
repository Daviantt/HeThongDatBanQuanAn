package vn.edu.giavien.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.edu.giavien.domain.Models.*;

@Repository
public class RestaurantRepository {
  private final JdbcTemplate jdbc;

  public RestaurantRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public JdbcTemplate jdbc() {
    return jdbc;
  }

  // A database row lock serializes schedule mutations, including simultaneous requests.
  // This must be called inside a service transaction, before checking availability.
  public void lockSchedule() {
    jdbc.queryForObject("SELECT id FROM app_lock WHERE id=1 FOR UPDATE", Integer.class);
  }

  public Settings settings() {
    return jdbc.queryForObject(
        "SELECT * FROM restaurant_settings WHERE id=1",
        (r, n) ->
            new Settings(
                r.getLong("deposit_per_table"),
                r.getInt("hold_minutes"),
                r.getInt("cleanup_minutes"),
                r.getInt("open_hour"),
                r.getInt("close_hour"),
                r.getInt("grace_minutes")));
  }

  private final RowMapper<Account> accountMapper =
      (r, n) ->
          new Account(
              r.getLong("id"),
              r.getString("email"),
              r.getString("password_hash"),
              r.getString("full_name"),
              r.getString("phone"),
              r.getString("role"));

  public Optional<Account> account(String email) {
    return jdbc
        .query(
            "SELECT * FROM app_user WHERE email=?", accountMapper, email.toLowerCase(Locale.ROOT))
        .stream()
        .findFirst();
  }

  public List<Account> accounts() {
    return jdbc.query("SELECT * FROM app_user ORDER BY id", accountMapper);
  }

  public void insertAccount(String email, String password, String name, String phone, String role) {
    jdbc.update(
        "INSERT INTO app_user(email,password_hash,full_name,phone,role) VALUES(?,?,?,?,?)",
        email,
        password,
        name,
        phone,
        role);
  }

  public List<DiningTable> tables() {
    return jdbc.query(
        "SELECT * FROM dining_table WHERE retired=FALSE ORDER BY id",
        (r, n) ->
            new DiningTable(
                r.getLong("id"),
                r.getString("code"),
                r.getString("zone"),
                r.getInt("map_x"),
                r.getInt("map_y"),
                r.getBoolean("active"),
                r.getInt("floor")));
  }

  public List<List<Long>> combinations() {
    return jdbc.query(
        "SELECT table_ids FROM table_combination ORDER BY id",
        (r, n) -> Arrays.stream(r.getString(1).split(",")).map(Long::valueOf).toList());
  }

  public List<Dish> dishes() {
    return jdbc.query("SELECT * FROM menu_item ORDER BY id", this::dishRow);
  }

  private Dish dishRow(ResultSet r, int row) throws SQLException {
    Integer stock = r.getObject("stock_quantity", Integer.class);
    return new Dish(
                r.getLong("id"),
                r.getString("name"),
                r.getString("description"),
                r.getString("category"),
                r.getLong("price"),
                r.getBoolean("available") && (stock == null || stock > 0),
                r.getString("illustration"));
  }

  public List<StockItem> stockItems() {
    return jdbc.query("SELECT * FROM menu_item ORDER BY id", (r, n) ->
        new StockItem(dishRow(r, n), r.getObject("stock_quantity", Integer.class), r.getInt("low_stock_threshold")));
  }

  public List<OrderLine> lines(String id) {
    return jdbc.query(
        "SELECT * FROM order_line WHERE reservation_id=? ORDER BY menu_item_id",
        (r, n) ->
            new OrderLine(
                r.getLong("menu_item_id"),
                r.getString("item_name"),
                r.getLong("unit_price"),
                r.getInt("quantity")),
        id);
  }

  public List<OrderLine> takeawayLines(String id) {
    return jdbc.query(
        "SELECT * FROM takeaway_order_line WHERE order_id=? ORDER BY menu_item_id",
        (r, n) -> new OrderLine(r.getLong("menu_item_id"), r.getString("item_name"), r.getLong("unit_price"), r.getInt("quantity")),
        id);
  }

  private TakeawayOrder takeawayRow(ResultSet r, int row) throws SQLException {
    String id = r.getString("id");
    return new TakeawayOrder(
        id, r.getLong("user_id"), r.getString("customer_name"), r.getString("customer_phone"),
        r.getObject("pickup_at", LocalDateTime.class), r.getLong("total"), r.getString("payment_method"),
        TakeawayStatus.valueOf(r.getString("status")), r.getObject("created_at", LocalDateTime.class),
        r.getObject("paid_at", LocalDateTime.class), r.getString("payment_reference"), r.getString("refund_reference"), List.of());
  }

  public Optional<TakeawayOrder> takeaway(String id) {
    return loadTakeaways("SELECT * FROM takeaway_order WHERE id=?", id).stream().findFirst();
  }

  public GroupPromotion promotion() {
    return jdbc.queryForObject("SELECT * FROM group_promotion WHERE id=1", (r, n) ->
        new GroupPromotion(r.getBoolean("enabled"), r.getInt("minimum_guests"), r.getInt("discount_percent")));
  }

  public List<TakeawayOrder> takeawayOrders(long userId) {
    return loadTakeaways(
        "SELECT * FROM takeaway_order WHERE user_id=? ORDER BY created_at DESC, id DESC",
        userId);
  }

  private List<TakeawayOrder> loadTakeaways(String sql, Object... args) {
    var orders = jdbc.query(sql, this::takeawayRow, args);
    if (orders.isEmpty()) return orders;
    Map<String, List<OrderLine>> lines = new HashMap<>();
    new NamedParameterJdbcTemplate(jdbc).query(
        "SELECT * FROM takeaway_order_line WHERE order_id IN (:ids) ORDER BY menu_item_id",
        Map.of("ids", orders.stream().map(TakeawayOrder::id).toList()),
        (org.springframework.jdbc.core.RowCallbackHandler) r -> lines.computeIfAbsent(r.getString("order_id"), key -> new ArrayList<>()).add(orderLine(r)));
    return orders.stream().map(o -> new TakeawayOrder(o.id(), o.userId(), o.customerName(), o.customerPhone(),
        o.pickupAt(), o.total(), o.paymentMethod(), o.status(), o.createdAt(), o.paidAt(), o.paymentReference(),
        o.refundReference(), lines.getOrDefault(o.id(), List.of()))).toList();
  }

  private OrderLine orderLine(ResultSet r) throws SQLException {
    return new OrderLine(r.getLong("menu_item_id"), r.getString("item_name"), r.getLong("unit_price"), r.getInt("quantity"));
  }

  public void insertTakeaway(TakeawayOrder order) {
    jdbc.update("INSERT INTO takeaway_order(id,user_id,customer_name,customer_phone,pickup_at,total,payment_method,status,created_at,paid_at,payment_reference) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        order.id(), order.userId(), order.customerName(), order.customerPhone(), order.pickupAt(), order.total(), order.paymentMethod(), order.status().name(), order.createdAt(), order.paidAt(), order.paymentReference());
    order.lines().forEach(line -> jdbc.update("INSERT INTO takeaway_order_line VALUES(?,?,?,?,?)", order.id(), line.menuItemId(), line.itemName(), line.unitPrice(), line.quantity()));
  }

  public List<Long> tableIds(String id) {
    return jdbc.queryForList(
        "SELECT table_id FROM reservation_table WHERE reservation_id=? ORDER BY table_id",
        Long.class,
        id);
  }

  private Booking bookingRow(ResultSet r, int row) throws SQLException {
    String id = r.getString("id");
    return new Booking(
        id,
        r.getLong("user_id"),
        r.getString("full_name"),
        r.getString("email"),
        r.getString("phone"),
        r.getObject("start_at", LocalDateTime.class),
        r.getObject("end_at", LocalDateTime.class),
        r.getInt("guests"),
        r.getString("notes"),
        BookingStatus.valueOf(r.getString("status")),
        PaymentStatus.valueOf(r.getString("payment_status")),
        r.getLong("deposit"),
        r.getLong("food_deposit"),
        r.getInt("promotion_minimum_guests"),
        r.getInt("promotion_percent"),
        r.getObject("created_at", LocalDateTime.class),
        r.getObject("hold_until", LocalDateTime.class),
        r.getObject("paid_at", LocalDateTime.class),
        r.getObject("released_at", LocalDateTime.class),
        List.of(), List.of(), List.of());
  }

  // Three queries per batch, regardless of the number of bookings in that batch.
  private List<Booking> loadBookings(String sql, Object... args) {
    List<Booking> bookings = jdbc.query(sql, this::bookingRow, args);
    if (bookings.isEmpty()) return bookings;
    var parameters = Map.of("ids", bookings.stream().map(Booking::id).toList());
    var named = new NamedParameterJdbcTemplate(jdbc);
    Map<String, List<Long>> tables = new HashMap<>();
    Map<String, List<String>> codes = new HashMap<>();
    Map<String, List<OrderLine>> lines = new HashMap<>();
    named.query("SELECT rt.reservation_id,t.id,t.code FROM reservation_table rt JOIN dining_table t ON t.id=rt.table_id WHERE rt.reservation_id IN (:ids) ORDER BY t.id",
        parameters, (org.springframework.jdbc.core.RowCallbackHandler) r -> {
          String id = r.getString("reservation_id");
          tables.computeIfAbsent(id, key -> new ArrayList<>()).add(r.getLong("id"));
          codes.computeIfAbsent(id, key -> new ArrayList<>()).add(r.getString("code"));
        });
    named.query("SELECT * FROM order_line WHERE reservation_id IN (:ids) ORDER BY menu_item_id", parameters,
        (org.springframework.jdbc.core.RowCallbackHandler) r -> lines.computeIfAbsent(r.getString("reservation_id"), key -> new ArrayList<>()).add(orderLine(r)));
    return bookings.stream().map(b -> new Booking(b.id(), b.userId(), b.customerName(), b.customerEmail(), b.customerPhone(),
        b.startAt(), b.endAt(), b.guests(), b.notes(), b.status(), b.paymentStatus(), b.deposit(), b.foodDeposit(),
        b.promotionMinimumGuests(), b.promotionPercent(), b.createdAt(), b.holdUntil(), b.paidAt(), b.releasedAt(), tables.getOrDefault(b.id(), List.of()),
        codes.getOrDefault(b.id(), List.of()), lines.getOrDefault(b.id(), List.of()))).toList();
  }

  private static final String BOOKING_SELECT =
      "SELECT b.*,u.full_name,u.email,u.phone FROM reservation b JOIN app_user u ON u.id=b.user_id"
          + " ";

  public Optional<Booking> booking(String id) {
    return loadBookings(BOOKING_SELECT + "WHERE b.id=?", id).stream().findFirst();
  }

  public List<Booking> bookings(Long userId) {
    return userId == null
        ? loadBookings(BOOKING_SELECT + "ORDER BY b.start_at DESC, b.id DESC")
        : loadBookings(
            BOOKING_SELECT + "WHERE b.user_id=? ORDER BY b.start_at DESC",
            userId);
  }

  public List<Booking> tableBoardBookings(LocalDateTime dayStart, LocalDateTime dayEnd,
      LocalDateTime now, int cleanup) {
    return loadBookings(BOOKING_SELECT + """
        WHERE b.status IN ('CONFIRMED','SEATED')
          OR (b.status='PENDING' AND b.hold_until>?)
          OR (b.status='COMPLETED' AND b.released_at>? AND b.released_at<?)
        ORDER BY b.start_at,b.id
        """, now, dayStart.minusMinutes(cleanup), dayEnd);
  }

  public Set<Long> busyTableIds(LocalDateTime start, LocalDateTime end, LocalDateTime now, int cleanup, String exclude) {
    String sql = "SELECT DISTINCT rt.table_id FROM reservation b JOIN reservation_table rt ON rt.reservation_id=b.id"
        + " WHERE (b.status='SEATED'"
        + " OR ((b.status='CONFIRMED' OR (b.status='PENDING' AND b.hold_until>?)) AND b.start_at>=? AND b.start_at<?)"
        + " OR (b.status='COMPLETED' AND b.released_at>?))";
    // No estimated duration: one unresolved reservation per table per service date.
    // A seated party continues occupying its tables across midnight until staff finishes it.
    List<Object> args = new ArrayList<>(List.of(now, start.toLocalDate().atStartOfDay(),
        start.toLocalDate().plusDays(1).atStartOfDay(), start.minusMinutes(cleanup)));
    if (exclude != null) { sql += " AND b.id<>?"; args.add(exclude); }
    return new HashSet<>(jdbc.queryForList(sql, Long.class, args.toArray()));
  }

  public Set<Long> occupiedTableIds(LocalDateTime now, int cleanup, String exclude) {
    return new HashSet<>(jdbc.queryForList("""
        SELECT DISTINCT rt.table_id FROM reservation b
        JOIN reservation_table rt ON rt.reservation_id=b.id
        WHERE b.id<>? AND (b.status='SEATED' OR (b.status='COMPLETED' AND b.released_at>?))
        """, Long.class, exclude, now.minusMinutes(cleanup)));
  }

  public boolean tableHasLiveBooking(long tableId, LocalDateTime now) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM reservation b JOIN reservation_table rt ON rt.reservation_id=b.id"
        + " WHERE rt.table_id=? AND (b.status IN ('SEATED','CONFIRMED') OR (b.status='PENDING' AND b.hold_until>?))",
        Integer.class, tableId, now) > 0;
  }

  public record Page<T>(List<T> items, int number, int size, long total) {
    public long pages() { return Math.max(1, (total + size - 1) / size); }
    public boolean hasPrevious() { return number > 0; }
    public boolean hasNext() { return number + 1 < pages(); }
  }

  private static final int PAGE_SIZE = 20;

  private int pageNumber(int requested, long total) {
    return (int) Math.max(0, Math.min(requested, Math.max(0, (total - 1) / PAGE_SIZE)));
  }

  public Page<Booking> bookingPage(Long userId, int requested, String search) {
    String where = " WHERE 1=1";
    List<Object> args = new ArrayList<>();
    if (userId != null) { where += " AND b.user_id=?"; args.add(userId); }
    if (search != null && !search.isBlank()) {
      where += " AND (LOCATE(?,LOWER('GV-' || SUBSTRING(b.id,1,8)))>0 OR LOCATE(?,LOWER(u.full_name))>0 OR LOCATE(?,u.phone)>0)";
      String term = search.strip().toLowerCase(Locale.ROOT);
      args.addAll(List.of(term, term, term));
    }
    long total = jdbc.queryForObject("SELECT COUNT(*) FROM reservation b JOIN app_user u ON u.id=b.user_id" + where, Long.class, args.toArray());
    int page = pageNumber(requested, total);
    args.add(PAGE_SIZE); args.add(page * PAGE_SIZE);
    return new Page<>(loadBookings(BOOKING_SELECT + where + " ORDER BY b.start_at DESC,b.id DESC LIMIT ? OFFSET ?", args.toArray()), page, PAGE_SIZE, total);
  }

  public Page<TakeawayOrder> takeawayPage(Long userId, int requested) {
    String where = userId == null ? "" : " WHERE user_id=?";
    List<Object> args = new ArrayList<>();
    if (userId != null) args.add(userId);
    long total = jdbc.queryForObject("SELECT COUNT(*) FROM takeaway_order" + where, Long.class, args.toArray());
    int page = pageNumber(requested, total);
    args.add(PAGE_SIZE); args.add(page * PAGE_SIZE);
    return new Page<>(loadTakeaways("SELECT * FROM takeaway_order" + where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", args.toArray()), page, PAGE_SIZE, total);
  }

  public record StaffCounts(long today, long confirmed, long seated, long refunds) {}

  public StaffCounts staffCounts(LocalDateTime now) {
    return jdbc.queryForObject("SELECT COUNT(CASE WHEN start_at>=? AND start_at<? THEN 1 END),"
        + " COUNT(CASE WHEN status='CONFIRMED' THEN 1 END),COUNT(CASE WHEN status='SEATED' THEN 1 END),"
        + " COUNT(CASE WHEN payment_status='REFUND_PENDING' THEN 1 END) FROM reservation",
        (r, n) -> new StaffCounts(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4)),
        now.toLocalDate().atStartOfDay(), now.toLocalDate().plusDays(1).atStartOfDay());
  }

  public void insertBooking(
      String id,
      long userId,
      LocalDateTime start,
      LocalDateTime end,
      int guests,
      String notes,
      long deposit,
      long foodDeposit,
      LocalDateTime now,
      LocalDateTime holdUntil,
      List<Long> tables) {
    jdbc.update(
        "INSERT INTO"
            + " reservation(id,user_id,start_at,end_at,guests,notes,status,payment_status,deposit,food_deposit,created_at,hold_until)"
            + " VALUES(?,?,?,?,?,?,'PENDING','UNPAID',?,?,?,?)",
        id,
        userId,
        start,
        end,
        guests,
        notes,
        deposit,
        foodDeposit,
        now,
        holdUntil);
    replaceTables(id, tables);
  }

  public void replaceTables(String id, List<Long> tables) {
    jdbc.update("DELETE FROM reservation_table WHERE reservation_id=?", id);
    tables.forEach(table -> jdbc.update("INSERT INTO reservation_table VALUES(?,?)", id, table));
  }

  public void replaceLines(String id, List<OrderLine> lines) {
    jdbc.update("DELETE FROM order_line WHERE reservation_id=?", id);
    lines.forEach(
        line ->
            jdbc.update(
                "INSERT INTO order_line VALUES(?,?,?,?,?)",
                id,
                line.menuItemId(),
                line.itemName(),
                line.unitPrice(),
                line.quantity()));
  }

  public void expire(LocalDateTime now) {
    jdbc.update(
        "UPDATE reservation SET status='EXPIRED' WHERE status='PENDING' AND hold_until<=?", now);
  }

  public void audit(String id, String actor, String action, LocalDateTime now) {
    jdbc.update(
        "INSERT INTO audit_event(reservation_id,actor,action,created_at) VALUES(?,?,?,?)",
        id,
        actor,
        action,
        now);
  }

  public List<AuditEvent> events(String id) {
    return jdbc.query(
        "SELECT * FROM audit_event WHERE reservation_id=? ORDER BY id DESC",
        (r, n) ->
            new AuditEvent(
                r.getString("actor"),
                r.getString("action"),
                r.getObject("created_at", LocalDateTime.class)),
        id);
  }

  public List<ChangeRequest> requests(String id) {
    return jdbc.query(
        "SELECT * FROM change_request WHERE reservation_id=? ORDER BY id DESC",
        (r, n) ->
            new ChangeRequest(
                r.getLong("id"),
                r.getString("reservation_id"),
                r.getString("message"),
                r.getObject("created_at", LocalDateTime.class),
                r.getString("status"),
                r.getString("response")),
        id);
  }
}
