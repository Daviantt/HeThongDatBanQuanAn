package vn.edu.giavien.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
    return jdbc.query(
        "SELECT * FROM menu_item ORDER BY id",
        (r, n) ->
            new Dish(
                r.getLong("id"),
                r.getString("name"),
                r.getString("description"),
                r.getString("category"),
                r.getLong("price"),
                r.getBoolean("available"),
                r.getString("illustration")));
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
        r.getObject("created_at", LocalDateTime.class),
        r.getObject("hold_until", LocalDateTime.class),
        r.getObject("paid_at", LocalDateTime.class),
        r.getObject("released_at", LocalDateTime.class),
        tableIds(id),
        jdbc.queryForList(
            "SELECT t.code FROM dining_table t JOIN reservation_table rt ON rt.table_id=t.id WHERE"
                + " rt.reservation_id=? ORDER BY t.id",
            String.class,
            id),
        lines(id));
  }

  private static final String BOOKING_SELECT =
      "SELECT b.*,u.full_name,u.email,u.phone FROM reservation b JOIN app_user u ON u.id=b.user_id"
          + " ";

  public Optional<Booking> booking(String id) {
    return jdbc.query(BOOKING_SELECT + "WHERE b.id=?", this::bookingRow, id).stream().findFirst();
  }

  public List<Booking> bookings(Long userId) {
    return userId == null
        ? jdbc.query(BOOKING_SELECT + "ORDER BY b.start_at DESC", this::bookingRow)
        : jdbc.query(
            BOOKING_SELECT + "WHERE b.user_id=? ORDER BY b.start_at DESC",
            this::bookingRow,
            userId);
  }

  public List<Booking> activeBookings(LocalDateTime now) {
    return jdbc.query(
        BOOKING_SELECT
            + "WHERE b.status IN ('CONFIRMED','SEATED') OR (b.status='PENDING' AND b.hold_until>?)"
            + " OR (b.status='COMPLETED' AND b.released_at>?)",
        this::bookingRow,
        now,
        now.minusMinutes(settings().cleanupMinutes()));
  }

  public void insertBooking(
      String id,
      long userId,
      LocalDateTime start,
      LocalDateTime end,
      int guests,
      String notes,
      long deposit,
      LocalDateTime now,
      LocalDateTime holdUntil,
      List<Long> tables) {
    jdbc.update(
        "INSERT INTO"
            + " reservation(id,user_id,start_at,end_at,guests,notes,status,payment_status,deposit,created_at,hold_until)"
            + " VALUES(?,?,?,?,?,?,'PENDING','UNPAID',?,?,?)",
        id,
        userId,
        start,
        end,
        guests,
        notes,
        deposit,
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
