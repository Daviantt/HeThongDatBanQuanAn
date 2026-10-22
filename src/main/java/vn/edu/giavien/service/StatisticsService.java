package vn.edu.giavien.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.giavien.data.RestaurantRepository;
import vn.edu.giavien.data.StatisticsRepository;
import vn.edu.giavien.data.StatisticsRepository.MonthlyDish;
import vn.edu.giavien.domain.Models.Dish;

@Service
public class StatisticsService {
  private final RestaurantRepository restaurant;
  private final StatisticsRepository statistics;

  public StatisticsService(RestaurantRepository restaurant, StatisticsRepository statistics) {
    this.restaurant = restaurant;
    this.statistics = statistics;
  }

  public enum Grouping {
    DAY("Ngày", "dd/MM/yyyy"), MONTH("Tháng", "MM/yyyy"), YEAR("Năm", "yyyy");

    private final String label;
    private final DateTimeFormatter formatter;

    Grouping(String label, String pattern) {
      this.label = label;
      this.formatter = DateTimeFormatter.ofPattern(pattern);
    }

    public String label() { return label; }

    public LocalDate period(LocalDate date) {
      return switch (this) {
        case DAY -> date;
        case MONTH -> date.withDayOfMonth(1);
        case YEAR -> date.withDayOfYear(1);
      };
    }

    public String format(LocalDate date) { return formatter.format(date); }
  }

  public record RevenueRow(String period, long dineIn, long takeaway, long orders) {
    public long total() { return dineIn + takeaway; }
  }

  public record DishRow(Dish dish, long quantity, long orders, List<MonthlyDish> bestMonths) {
    public boolean bestseller() { return !bestMonths.isEmpty(); }
    public String bestMonthsLabel() {
      return String.join(", ", bestMonths.stream().map(m ->
          m.month().format(DateTimeFormatter.ofPattern("MM/yyyy")) + ": " + m.orders() + " đơn").toList());
    }
  }

  public record Report(long dineIn, long takeaway, long orders, long quantity,
                       List<RevenueRow> periods, List<DishRow> dishes) {
    public long total() { return dineIn + takeaway; }
    public long bestsellers() { return dishes.stream().filter(DishRow::bestseller).count(); }
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Report report(LocalDate start, LocalDate end, Grouping grouping) {
    if (start == null || end == null || grouping == null)
      throw new IllegalArgumentException("Vui lòng chọn đủ ngày bắt đầu, ngày kết thúc và cách thống kê.");
    if (start.getYear() < 1900 || end.getYear() > 9999 || end.isBefore(start))
      throw new IllegalArgumentException("Ngày kết thúc phải từ ngày bắt đầu trở đi; năm hợp lệ từ 1900 đến 9999.");
    var from = start.atStartOfDay();
    var until = end.plusDays(1).atStartOfDay();
    var daily = statistics.revenue(from, until);
    var periods = new TreeMap<LocalDate, long[]>();
    long dineIn = 0, takeaway = 0, orders = 0;
    for (var row : daily) {
      dineIn += row.dineIn();
      takeaway += row.takeaway();
      orders += row.orders();
      long[] totals = periods.computeIfAbsent(grouping.period(row.date()), key -> new long[3]);
      totals[0] += row.dineIn();
      totals[1] += row.takeaway();
      totals[2] += row.orders();
    }
    var revenueRows = periods.entrySet().stream().map(e -> new RevenueRow(
        grouping.format(e.getKey()), e.getValue()[0], e.getValue()[1], e.getValue()[2])).toList();
    Map<Long, List<MonthlyDish>> monthlyDishes = new HashMap<>();
    for (var row : statistics.dishes(from, until))
      monthlyDishes.computeIfAbsent(row.dishId(), key -> new ArrayList<>()).add(row);
    // Drive the report from the current catalogue: new and unavailable dishes remain visible at zero sales.
    var dishes = restaurant.dishes().stream().map(dish -> {
      var months = monthlyDishes.getOrDefault(dish.id(), List.of());
      return new DishRow(dish, months.stream().mapToLong(MonthlyDish::quantity).sum(),
          months.stream().mapToLong(MonthlyDish::orders).sum(),
          months.stream().filter(m -> m.orders() > 700).toList());
    }).sorted(Comparator.comparingLong(DishRow::quantity).reversed()
        .thenComparing(row -> row.dish().id())).toList();
    return new Report(dineIn, takeaway, orders, dishes.stream().mapToLong(DishRow::quantity).sum(),
        revenueRows, dishes);
  }
}
