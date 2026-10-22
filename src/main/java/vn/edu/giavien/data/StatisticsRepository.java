package vn.edu.giavien.data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Aggregates all completed orders, independently of the operation pages' pagination. */
@Repository
public class StatisticsRepository {
  private final JdbcTemplate jdbc;

  public StatisticsRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record DailyRevenue(LocalDate date, long dineIn, long takeaway, long orders) {}
  public record MonthlyDish(long dishId, YearMonth month, long quantity, long orders) {}

  // Legacy completed orders have no completion timestamp; retain them using their scheduled date.
  private static final String BOOKING_TIME = "COALESCE(b.released_at,b.end_at)";
  private static final String TAKEAWAY_TIME = "COALESCE(t.completed_at,t.pickup_at)";

  public List<DailyRevenue> revenue(LocalDateTime from, LocalDateTime until) {
    return jdbc.query("""
        SELECT sale_date, SUM(dine_in) AS dine_in, SUM(takeaway) AS takeaway, COUNT(*) AS orders
        FROM (
          SELECT CAST(%s AS DATE) AS sale_date,
            COALESCE(SUM(l.unit_price * CAST(l.quantity AS BIGINT)),0)
              - FLOOR(COALESCE(SUM(l.unit_price * CAST(l.quantity AS BIGINT)),0)
                * CASE WHEN b.guests >= b.promotion_minimum_guests
                  THEN b.promotion_percent ELSE 0 END / 100.0) AS dine_in,
            CAST(0 AS BIGINT) AS takeaway
          FROM reservation b LEFT JOIN order_line l ON l.reservation_id=b.id
          WHERE b.status='COMPLETED' AND %s >= ? AND %s < ?
          GROUP BY b.id,b.released_at,b.end_at,b.guests,b.promotion_minimum_guests,b.promotion_percent
          UNION ALL
          SELECT CAST(%s AS DATE),CAST(0 AS BIGINT),t.total
          FROM takeaway_order t
          WHERE t.status='COMPLETED' AND %s >= ? AND %s < ?
        ) sales
        GROUP BY sale_date ORDER BY sale_date
        """.formatted(BOOKING_TIME, BOOKING_TIME, BOOKING_TIME,
            TAKEAWAY_TIME, TAKEAWAY_TIME, TAKEAWAY_TIME),
        (r, n) -> new DailyRevenue(r.getObject("sale_date", LocalDate.class),
            r.getLong("dine_in"), r.getLong("takeaway"), r.getLong("orders")),
        from, until, from, until);
  }

  public List<MonthlyDish> dishes(LocalDateTime from, LocalDateTime until) {
    // Each source has one line per dish per order. UNION ALL preserves distinct orders
    // even if a reservation and a takeaway happen to have the same identifier.
    return jdbc.query("""
        SELECT menu_item_id,EXTRACT(YEAR FROM sold_at) AS sale_year,
          EXTRACT(MONTH FROM sold_at) AS sale_month,
          SUM(CAST(quantity AS BIGINT)) AS quantity,COUNT(*) AS orders
        FROM (
          SELECT l.menu_item_id,l.quantity,%s AS sold_at
          FROM reservation b JOIN order_line l ON l.reservation_id=b.id
          WHERE b.status='COMPLETED' AND %s >= ? AND %s < ?
          UNION ALL
          SELECT l.menu_item_id,l.quantity,%s AS sold_at
          FROM takeaway_order t JOIN takeaway_order_line l ON l.order_id=t.id
          WHERE t.status='COMPLETED' AND %s >= ? AND %s < ?
        ) sales
        GROUP BY menu_item_id,EXTRACT(YEAR FROM sold_at),EXTRACT(MONTH FROM sold_at)
        ORDER BY menu_item_id,sale_year,sale_month
        """.formatted(BOOKING_TIME, BOOKING_TIME, BOOKING_TIME,
            TAKEAWAY_TIME, TAKEAWAY_TIME, TAKEAWAY_TIME),
        (r, n) -> new MonthlyDish(r.getLong("menu_item_id"),
            YearMonth.of(r.getInt("sale_year"), r.getInt("sale_month")),
            r.getLong("quantity"), r.getLong("orders")),
        from, until, from, until);
  }
}
