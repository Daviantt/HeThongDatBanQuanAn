package vn.edu.giavien.web;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import vn.edu.giavien.service.StatisticsService;
import vn.edu.giavien.service.StatisticsService.Grouping;

@Controller
public class StatisticsController {
  private final StatisticsService statistics;
  private final Clock clock;

  public StatisticsController(StatisticsService statistics, Clock clock) {
    this.statistics = statistics;
    this.clock = clock;
  }

  @GetMapping("/admin/statistics")
  public String statistics(@RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @RequestParam(defaultValue = "MONTH") String groupBy, Model model, HttpServletResponse response) {
    var today = LocalDate.now(clock);
    model.addAttribute("statisticsPage", true);
    if (startDate == null) startDate = today.withDayOfMonth(1).toString();
    if (endDate == null) endDate = today.toString();
    model.addAttribute("startDate", startDate);
    model.addAttribute("endDate", endDate);
    model.addAttribute("groupBy", groupBy.toUpperCase(Locale.ROOT));
    model.addAttribute("groupings", Grouping.values());
    model.addAttribute("today", today);
    model.addAttribute("monthStart", today.withDayOfMonth(1));
    model.addAttribute("yearStart", today.withDayOfYear(1));
    try {
      Grouping grouping;
      try {
        grouping = Grouping.valueOf(groupBy.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Vui lòng chọn thống kê theo ngày, tháng hoặc năm.");
      }
      model.addAttribute("report", statistics.report(LocalDate.parse(startDate), LocalDate.parse(endDate), grouping));
      model.addAttribute("periodLabel", grouping.label());
    } catch (DateTimeParseException e) {
      response.setStatus(400);
      model.addAttribute("filterError", "Ngày không hợp lệ. Vui lòng nhập ngày theo định dạng yyyy-MM-dd.");
    } catch (IllegalArgumentException e) {
      response.setStatus(400);
      model.addAttribute("filterError", e.getMessage());
    }
    return "admin-statistics";
  }
}
