package vn.edu.giavien.web;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import vn.edu.giavien.service.*;

@RestController
public class BookingApi {
  private final BookingService service;
  private final VnpayPaymentService payments;

  public BookingApi(BookingService service, VnpayPaymentService payments) {
    this.service = service;
    this.payments = payments;
  }

  @GetMapping("/api/availability")
  public BookingService.Availability availability(
      @RequestParam LocalDateTime startAt,
      @RequestParam LocalDateTime endAt,
      @RequestParam int guests) {
    return service.availability(startAt, endAt, guests);
  }

  @PostMapping("/api/bookings")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, String> create(
      @RequestBody BookingService.BookingInput input, Principal principal) {
    String id = service.create(service.account(principal.getName()), input);
    return Map.of("id", id, "url", "/bookings/" + id);
  }

  @GetMapping("/api/bookings/{id}/payment-status")
  public org.springframework.http.ResponseEntity<VnpayPaymentService.Result> paymentStatus(
      @PathVariable String id, Principal principal) {
    return org.springframework.http.ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(payments.statusFor(id, service.account(principal.getName())));
  }

  @GetMapping("/payment/vnpay/ipn")
  public Map<String, String> ipn(
      @RequestParam org.springframework.util.MultiValueMap<String, String> parameters) {
    if (parameters.values().stream().anyMatch(values -> values.size() != 1))
      return Map.of("RspCode", "97", "Message", "Invalid parameters");
    try {
      String code = payments.ipn(parameters.toSingleValueMap());
      return Map.of(
          "RspCode",
          code,
          "Message",
          switch (code) {
            case "00" -> "Confirm Success";
            case "01" -> "Order not found";
            case "02" -> "Order already confirmed";
            case "04" -> "Invalid amount";
            case "97" -> "Invalid signature";
            default -> "Invalid data";
          });
    } catch (RuntimeException e) {
      org.slf4j.LoggerFactory.getLogger(BookingApi.class).error("Could not record VNPAY IPN", e);
      return Map.of("RspCode", "99", "Message", "Unable to update payment");
    }
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, String> badRequest(IllegalArgumentException error) {
    return Map.of("error", error.getMessage());
  }

  @ExceptionHandler(AccessDeniedException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public Map<String, String> forbidden() {
    return Map.of("error", "Bạn không có quyền thực hiện thao tác này.");
  }
}
