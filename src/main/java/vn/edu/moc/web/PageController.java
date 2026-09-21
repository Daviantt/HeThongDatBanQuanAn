package vn.edu.moc.web;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.edu.moc.data.RestaurantRepository;
import vn.edu.moc.domain.BookingPolicy;
import vn.edu.moc.domain.Models.*;
import vn.edu.moc.service.*;

@Controller
public class PageController {
  private final RestaurantRepository repo;
  private final BookingService service;
  private final VnpayGateway gateway;
  private final boolean demo;

  public PageController(
      RestaurantRepository repo,
      BookingService service,
      VnpayGateway gateway,
      @Value("${app.demo}") boolean demo) {
    this.repo = repo;
    this.service = service;
    this.gateway = gateway;
    this.demo = demo;
  }

  private Account actor(Principal principal) {
    return service.account(principal.getName());
  }

  @GetMapping("/")
  public String home(Model model) {
    model.addAttribute("dishes", repo.dishes().stream().filter(Dish::available).limit(3).toList());
    return "home";
  }

  @GetMapping("/menu")
  public String menu(Model model) {
    model.addAttribute("dishes", repo.dishes());
    return "menu";
  }

  @GetMapping("/login")
  public String login() {
    return "login";
  }

  @GetMapping("/register")
  public String register() {
    return "register";
  }

  @PostMapping("/register")
  public String register(
      @RequestParam String email,
      @RequestParam String password,
      @RequestParam String fullName,
      @RequestParam String phone,
      Model model) {
    try {
      service.register(email, password, fullName, phone);
      return "redirect:/login?registered";
    } catch (IllegalArgumentException e) {
      model.addAttribute("formError", e.getMessage());
      model.addAttribute("email", email);
      model.addAttribute("fullName", fullName);
      model.addAttribute("phone", phone);
      return "register";
    }
  }

  @GetMapping("/book")
  public String book(Model model) {
    model.addAttribute("dishes", repo.dishes().stream().filter(Dish::available).toList());
    return "book";
  }

  @GetMapping("/bookings")
  public String bookings(Principal principal, Model model) {
    service.expireHolds();
    model.addAttribute("bookings", repo.bookings(actor(principal).id()));
    return "bookings";
  }

  @GetMapping("/bookings/{id}")
  public String detail(@PathVariable String id, Principal principal, Model model) {
    service.expireHolds();
    Account actor = actor(principal);
    Booking b = service.accessible(id, actor);
    model.addAttribute("booking", b);
    model.addAttribute("owner", b.userId() == actor.id());
    model.addAttribute(
        "canEdit", b.userId() == actor.id() && BookingPolicy.editable(b, service.now()));
    model.addAttribute(
        "canCancel", b.userId() == actor.id() && BookingPolicy.cancellable(b, service.now()));
    model.addAttribute("refundable", BookingPolicy.refundable(service.now(), b.startAt()));
    model.addAttribute("dishes", repo.dishes());
    model.addAttribute("requests", repo.requests(id));
    Map<Long, Integer> quantities = new HashMap<>();
    b.lines().forEach(l -> quantities.put(l.menuItemId(), l.quantity()));
    model.addAttribute("quantities", quantities);
    model.addAttribute("events", repo.events(id));
    return "detail";
  }

  @PostMapping("/bookings/{id}/cancel")
  public String cancel(@PathVariable String id, Principal principal, RedirectAttributes flash) {
    service.cancel(id, actor(principal), false);
    flash.addFlashAttribute("success", "Đã hủy lượt đặt. Bạn có thể xem trạng thái cọc bên dưới.");
    return "redirect:/bookings/" + id;
  }

  @PostMapping("/bookings/{id}/items")
  public String edit(
      @PathVariable String id,
      @RequestParam Map<String, String> form,
      Principal principal,
      RedirectAttributes flash) {
    Map<Long, Integer> items = new HashMap<>();
    form.forEach(
        (key, value) -> {
          if (key.startsWith("qty_"))
            items.put(Long.parseLong(key.substring(4)), Integer.parseInt(value));
        });
    service.editItems(id, actor(principal), items, form.get("notes"));
    flash.addFlashAttribute("success", "Đã cập nhật món và ghi chú.");
    return "redirect:/bookings/" + id;
  }

  @PostMapping("/bookings/{id}/request-change")
  public String request(
      @PathVariable String id,
      @RequestParam String message,
      Principal principal,
      RedirectAttributes flash) {
    service.requestChange(id, actor(principal), message);
    flash.addFlashAttribute(
        "success", "Đã gửi yêu cầu. Bàn và giờ hiện tại được giữ nguyên trong lúc chờ nhân viên.");
    return "redirect:/bookings/" + id;
  }

  @PostMapping("/bookings/{id}/demo-pay")
  public String demoPay(@PathVariable String id, Principal principal, RedirectAttributes flash) {
    BookingService.require(demo, "Chế độ thanh toán demo đã tắt.");
    service.demoPay(id, actor(principal));
    flash.addFlashAttribute("success", "Thanh toán DEMO thành công — không phát sinh tiền thật.");
    return "redirect:/bookings/" + id;
  }

  @PostMapping("/bookings/{id}/pay")
  public String pay(@PathVariable String id, Principal principal, HttpServletRequest request) {
    service.expireHolds();
    Booking b = service.accessible(id, actor(principal));
    BookingService.require(
        b.userId() == actor(principal).id() && b.status() == BookingStatus.PENDING,
        "Lượt đặt không thể thanh toán.");
    return "redirect:" + gateway.paymentUrl(b, request.getRemoteAddr());
  }

  @GetMapping("/payment/vnpay/return")
  public String paymentReturn(@RequestParam Map<String, String> parameters, Model model) {
    model.addAttribute("verified", gateway.verified(parameters));
    // Browser return never changes payment state. Only a signed IPN can do that.
    return "payment-result";
  }

  @GetMapping("/staff")
  public String staff(Model model) {
    service.expireHolds();
    var bookings = repo.bookings(null);
    model.addAttribute("bookings", bookings);
    model.addAttribute(
        "todayCount",
        bookings.stream()
            .filter(b -> b.startAt().toLocalDate().equals(service.now().toLocalDate()))
            .count());
    model.addAttribute(
        "confirmedCount",
        bookings.stream().filter(b -> b.status() == BookingStatus.CONFIRMED).count());
    model.addAttribute(
        "seatedCount", bookings.stream().filter(b -> b.status() == BookingStatus.SEATED).count());
    model.addAttribute(
        "refundCount",
        bookings.stream().filter(b -> b.paymentStatus() == PaymentStatus.REFUND_PENDING).count());
    return "staff";
  }

  @PostMapping("/staff/bookings/{id}/action")
  public String action(
      @PathVariable String id,
      @RequestParam String action,
      @RequestParam(defaultValue = "") String reference,
      Principal principal,
      RedirectAttributes flash) {
    if (action.equals("cancel")) service.cancel(id, actor(principal), true);
    else service.staffAction(id, actor(principal), action, reference);
    flash.addFlashAttribute("success", "Đã cập nhật lượt đặt.");
    return "redirect:/bookings/" + id;
  }

  @PostMapping("/staff/bookings/{id}/reschedule")
  public String reschedule(
      @PathVariable String id,
      @RequestParam LocalDateTime startAt,
      @RequestParam LocalDateTime endAt,
      @RequestParam int guests,
      @RequestParam String tableIds,
      @RequestParam String response,
      Principal principal,
      RedirectAttributes flash) {
    List<Long> ids = service.resolveTableNumbers(tableIds);
    service.reschedule(id, actor(principal), startAt, endAt, guests, ids, response);
    flash.addFlashAttribute("success", "Đã cập nhật bàn và lịch mới.");
    return "redirect:/bookings/" + id;
  }

  @PostMapping("/staff/bookings/{id}/reject")
  public String reject(
      @PathVariable String id,
      @RequestParam String response,
      Principal principal,
      RedirectAttributes flash) {
    service.rejectChange(id, actor(principal), response);
    flash.addFlashAttribute("success", "Đã phản hồi yêu cầu.");
    return "redirect:/bookings/" + id;
  }

  @GetMapping("/admin")
  public String admin(Model model) {
    model.addAttribute("dishes", repo.dishes());
    model.addAttribute("tables", repo.tables());
    model.addAttribute("combinations", repo.combinations());
    model.addAttribute("accounts", repo.accounts());
    return "admin";
  }

  @PostMapping("/admin/settings")
  public String settings(
      @RequestParam long deposit,
      @RequestParam int hold,
      @RequestParam int cleanup,
      @RequestParam int grace,
      Principal principal,
      RedirectAttributes flash) {
    service.updateSettings(actor(principal), deposit, hold, cleanup, grace);
    flash.addFlashAttribute("success", "Đã lưu cấu hình. Tiền cọc của lượt đặt cũ không thay đổi.");
    return "redirect:/admin";
  }

  @PostMapping("/admin/staff")
  public String createStaff(
      @RequestParam String email,
      @RequestParam String password,
      @RequestParam String fullName,
      @RequestParam String phone,
      Principal principal,
      RedirectAttributes flash) {
    service.createStaff(actor(principal), email, password, fullName, phone);
    flash.addFlashAttribute("success", "Đã tạo tài khoản nhân viên.");
    return "redirect:/admin";
  }

  @PostMapping("/admin/dishes")
  public String dish(
      @RequestParam long id,
      @RequestParam String name,
      @RequestParam String description,
      @RequestParam String category,
      @RequestParam long price,
      @RequestParam(defaultValue = "false") boolean available,
      Principal principal,
      RedirectAttributes flash) {
    service.saveDish(actor(principal), id, name, description, category, price, available);
    flash.addFlashAttribute("success", "Đã lưu món ăn.");
    return "redirect:/admin";
  }

  @PostMapping("/admin/tables/{id}/toggle")
  public String toggle(@PathVariable long id, Principal principal, RedirectAttributes flash) {
    service.toggleTable(actor(principal), id);
    flash.addFlashAttribute("success", "Đã cập nhật trạng thái bàn.");
    return "redirect:/admin";
  }

  @PostMapping("/admin/combinations")
  public String combo(
      @RequestParam String tableIds,
      @RequestParam(defaultValue = "false") boolean remove,
      Principal principal,
      RedirectAttributes flash) {
    service.saveCombination(actor(principal), tableIds, remove);
    flash.addFlashAttribute("success", "Đã cập nhật tổ hợp ghép bàn.");
    return "redirect:/admin";
  }
}
