package vn.edu.giavien.web;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.edu.giavien.service.BookingService;
import vn.edu.giavien.service.TakeawayService;

@RestController
public class TakeawayApi {
  private final TakeawayService orders;
  private final BookingService bookings;

  public TakeawayApi(TakeawayService orders, BookingService bookings) {
    this.orders = orders;
    this.bookings = bookings;
  }

  @PostMapping("/api/takeaway-orders")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, String> create(@RequestBody TakeawayService.Input input, Principal principal) {
    String id = orders.create(bookings.account(principal.getName()), input);
    return Map.of("id", id, "url", "/takeaway/orders/" + id, "paymentUrl", "/takeaway/orders/" + id + "/demo-payment");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, String> invalid(IllegalArgumentException error) {
    return Map.of("error", error.getMessage());
  }
}
