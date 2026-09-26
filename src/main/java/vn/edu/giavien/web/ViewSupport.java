package vn.edu.giavien.web;

import java.security.Principal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import vn.edu.giavien.data.RestaurantRepository;
import vn.edu.giavien.service.BookingService;
import vn.edu.giavien.service.DemoPaymentService;
import vn.edu.giavien.service.VnpayGateway;

@Component("fmt")
public class ViewSupport {
  private final RestaurantRepository repo;

  public ViewSupport(RestaurantRepository repo) {
    this.repo = repo;
  }

  public String tableNumbers(java.util.List<Long> ids) {
    var tables = repo.tables();
    return String.join(
        ",",
        ids.stream()
            .map(
                id ->
                    tables.stream()
                        .filter(t -> t.id() == id)
                        .findFirst()
                        .map(t -> t.code().substring(1))
                        .orElse(""))
            .filter(code -> !code.isEmpty())
            .toList());
  }

  public boolean hasRetiredTables(java.util.List<Long> ids) {
    var currentIds = repo.tables().stream().map(t -> t.id()).toList();
    return ids.stream().anyMatch(id -> !currentIds.contains(id));
  }

  public String tableLabels(java.util.List<Long> ids) {
    var tables = repo.tables();
    return String.join(
        " + ",
        ids.stream()
            .map(id -> tables.stream().filter(t -> t.id() == id).findFirst().orElseThrow().code())
            .toList());
  }

  public String money(long value) {
    return NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(value) + " ₫";
  }

  public String date(LocalDateTime value) {
    return value.format(DateTimeFormatter.ofPattern("HH:mm · dd/MM/yyyy"));
  }

  public String inputDate(LocalDateTime value) {
    return value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
  }
}

@ControllerAdvice
class SharedModel {
  private final RestaurantRepository repo;
  private final BookingService service;
  private final VnpayGateway gateway;
  private final DemoPaymentService demoPayments;
  private final boolean demo;

  SharedModel(
      RestaurantRepository repo,
      BookingService service,
      VnpayGateway gateway,
      DemoPaymentService demoPayments,
      @Value("${app.demo}") boolean demo) {
    this.repo = repo;
    this.service = service;
    this.gateway = gateway;
    this.demoPayments = demoPayments;
    this.demo = demo;
  }

  @ModelAttribute
  void common(Model model, Principal principal) {
    var account = principal == null ? null : repo.account(principal.getName()).orElse(null);
    model.addAttribute("currentUser", account);
    model.addAttribute("isStaff", account != null && service.staff(account));
    model.addAttribute("isAdmin", account != null && account.role().equals("ADMIN"));
    model.addAttribute("demo", demo);
    model.addAttribute("gatewayReady", gateway.configured());
    model.addAttribute("demoPaymentEnabled", demoPayments.enabled());
    model.addAttribute("settings", repo.settings());
    model.addAttribute("now", service.now());
  }
}
