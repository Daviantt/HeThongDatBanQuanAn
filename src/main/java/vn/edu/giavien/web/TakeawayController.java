package vn.edu.giavien.web;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import vn.edu.giavien.service.BookingService;
import vn.edu.giavien.service.TakeawayService;

@Controller
public class TakeawayController {
  private final TakeawayService orders;
  private final BookingService bookings;
  private final String publicBaseUrl;

  public TakeawayController(
      TakeawayService orders,
      BookingService bookings,
      @Value("${app.payment.demo-base-url:}") String publicBaseUrl) {
    this.orders = orders;
    this.bookings = bookings;
    this.publicBaseUrl = publicBaseUrl.strip().replaceAll("/+$", "");
  }

  @GetMapping("/takeaway")
  public String checkout(Model model, Principal principal) {
    var customer = bookings.account(principal.getName());
    model.addAttribute("customer", customer);
    return "takeaway";
  }

  @GetMapping("/takeaway/orders/{id}")
  public String confirmation(@PathVariable String id, Principal principal, Model model) {
    model.addAttribute("order", orders.accessible(id, bookings.account(principal.getName())));
    return "takeaway-confirmation";
  }

  @PostMapping("/takeaway/orders/{id}/demo-payment")
  public String start(@PathVariable String id, Principal principal) {
    return "redirect:/takeaway/payment/" + orders.startDemoPayment(id, bookings.account(principal.getName()));
  }

  @GetMapping("/takeaway/payment/{token}")
  public String payment(@PathVariable String token, Model model, HttpServletRequest request) {
    var state = orders.paymentStatus(token);
    model.addAttribute("payment", state);
    model.addAttribute("token", token);
    model.addAttribute("qrUrl", baseUrl(request) + "/takeaway/payment/" + token);
    return "takeaway-payment";
  }

  @GetMapping("/takeaway/payment/{token}/status")
  @ResponseBody
  public ResponseEntity<TakeawayService.PaymentState> status(@PathVariable String token) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(orders.paymentStatus(token));
  }

  @PostMapping("/takeaway/payment/{token}/confirm")
  public String confirm(@PathVariable String token, RedirectAttributes flash) {
    try {
      orders.confirmDemoPayment(token);
    } catch (IllegalArgumentException error) {
      flash.addFlashAttribute("error", error.getMessage());
    }
    return "redirect:/takeaway/payment/" + token;
  }

  @GetMapping(value = "/takeaway/payment/{token}/qr.png", produces = "image/png")
  @ResponseBody
  public ResponseEntity<byte[]> qr(@PathVariable String token, HttpServletRequest request) throws Exception {
    orders.paymentStatus(token);
    var matrix = new QRCodeWriter().encode(baseUrl(request) + "/takeaway/payment/" + token, BarcodeFormat.QR_CODE, 320, 320);
    var image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < matrix.getHeight(); y++) for (int x = 0; x < matrix.getWidth(); x++) image.setRGB(x, y, matrix.get(x, y) ? 0 : 0xffffff);
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(output.toByteArray());
  }

  private String baseUrl(HttpServletRequest request) {
    if (!publicBaseUrl.isEmpty()) {
      URI uri = URI.create(publicBaseUrl);
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null)
        throw new IllegalArgumentException("Địa chỉ QR demo phải là URL http/https hợp lệ.");
      return publicBaseUrl;
    }
    var builder = ServletUriComponentsBuilder.fromRequestUri(request).replacePath(request.getContextPath());
    if (Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(request.getServerName())) {
      String lan = lanAddress();
      if (lan != null) builder.host(lan);
    }
    return builder.build().toUriString();
  }

  private String lanAddress() {
    try {
      for (var adapter : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!adapter.isUp() || adapter.isLoopback() || adapter.isVirtual()) continue;
        for (var address : Collections.list(adapter.getInetAddresses()))
          if (address instanceof Inet4Address && address.isSiteLocalAddress()) return address.getHostAddress();
      }
    } catch (java.net.SocketException ignored) {
      // The current computer can still use the QR page via its own browser.
    }
    return null;
  }
}
