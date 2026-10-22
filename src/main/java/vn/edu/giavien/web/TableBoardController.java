package vn.edu.giavien.web;

import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.edu.giavien.service.BookingService;
import vn.edu.giavien.service.TableBoardService;

@Controller
public class TableBoardController {
  private final BookingService bookings;
  private final TableBoardService tables;

  public TableBoardController(BookingService bookings, TableBoardService tables) {
    this.bookings = bookings;
    this.tables = tables;
  }

  @GetMapping({"/staff/tables", "/staff/tables/fragment"})
  public String board(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response, Model model) {
    bookings.expireHolds();
    LocalDate selected = date == null ? bookings.now().toLocalDate() : date;
    model.addAttribute("board", tables.board(selected));
    model.addAttribute("tableBoardPage", true);
    response.setHeader("Cache-Control", "no-store");
    return request.getRequestURI().endsWith("/fragment") ? "staff-tables :: board" : "staff-tables";
  }

  @PostMapping("/staff/tables/bookings/{id}/action")
  public String action(@PathVariable String id, @RequestParam String action,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      Principal principal, RedirectAttributes flash) {
    if (!Set.of("checkin", "complete", "noshow", "cancel").contains(action))
      throw new IllegalArgumentException("Thao tác quản lý bàn không hợp lệ.");
    var actor = bookings.account(principal.getName());
    if (action.equals("cancel")) bookings.cancel(id, actor, true);
    else bookings.staffAction(id, actor, action, "");
    flash.addFlashAttribute("success", action.equals("complete")
        ? "Đã kết thúc phục vụ. Thời gian dọn bàn bắt đầu từ lúc xác nhận."
        : "Đã cập nhật trạng thái bàn.");
    return "redirect:/staff/tables?date=" + date;
  }
}
