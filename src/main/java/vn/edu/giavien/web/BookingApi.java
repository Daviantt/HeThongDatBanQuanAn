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
  private final VnpayGateway gateway;

  public BookingApi(BookingService service, VnpayGateway gateway) {
    this.service = service;
    this.gateway = gateway;
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

  @GetMapping("/payment/vnpay/ipn")
  public Map<String, String> ipn(@RequestParam Map<String, String> parameters) {
    if (!gateway.verified(parameters))
      return Map.of("RspCode", "97", "Message", "Invalid signature");
    try {
      long raw = Long.parseLong(parameters.getOrDefault("vnp_Amount", "-1"));
      if (raw < 0 || raw % 100 != 0) return Map.of("RspCode", "04", "Message", "Invalid amount");
      String reference = parameters.getOrDefault("vnp_TransactionNo", "");
      boolean success =
          "00".equals(parameters.get("vnp_ResponseCode"))
              && "00".equals(parameters.get("vnp_TransactionStatus"));
      if (success && !reference.matches("[0-9]{1,30}"))
        return Map.of("RspCode", "99", "Message", "Invalid transaction");
      String code =
          service.gatewayPayment(
              parameters.getOrDefault("vnp_TxnRef", ""), raw / 100, reference, success);
      return Map.of(
          "RspCode",
          code,
          "Message",
          switch (code) {
            case "00" -> "Confirm Success";
            case "01" -> "Order not found";
            case "02" -> "Order already confirmed";
            default -> "Invalid amount";
          });
    } catch (IllegalArgumentException e) {
      return Map.of("RspCode", "99", "Message", "Invalid data");
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
