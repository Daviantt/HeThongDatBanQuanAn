package vn.edu.giavien.web;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import vn.edu.giavien.service.BookingService;
import vn.edu.giavien.service.DemoPaymentService;

@Controller
public class DemoPaymentController {
  private final DemoPaymentService payments;
  private final BookingService bookings;
  private final String publicBaseUrl;

  public DemoPaymentController(
      DemoPaymentService payments,
      BookingService bookings,
      @Value("${app.payment.demo-base-url:}") String publicBaseUrl) {
    this.payments = payments;
    this.bookings = bookings;
    this.publicBaseUrl = publicBaseUrl.strip().replaceAll("/+$", "");
  }

  @PostMapping("/bookings/{id}/demo-payment")
  public String start(@PathVariable String id, Principal principal) {
    return "redirect:/payment/demo/" + payments.start(id, bookings.account(principal.getName()));
  }

  @GetMapping("/payment/demo/{token}")
  public String page(
      @PathVariable String token,
      Model model,
      HttpServletRequest request,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
    model.addAttribute("payment", payments.status(token));
    model.addAttribute("token", token);
    model.addAttribute("qrUrl", paymentUrl(token, request));
    return "demo-payment";
  }

  @GetMapping("/payment/demo/{token}/status")
  @ResponseBody
  public ResponseEntity<DemoPaymentService.State> status(@PathVariable String token) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payments.status(token));
  }

  @PostMapping("/payment/demo/{token}/confirm")
  public String confirm(@PathVariable String token, RedirectAttributes flash) {
    try {
      payments.confirm(token);
    } catch (IllegalArgumentException ex) {
      flash.addFlashAttribute("error", ex.getMessage());
    }
    return "redirect:/payment/demo/" + token;
  }

  @GetMapping(value = "/payment/demo/{token}/qr.png", produces = "image/png")
  @ResponseBody
  public ResponseEntity<byte[]> qr(@PathVariable String token, HttpServletRequest request)
      throws Exception {
    payments.status(token);
    var matrix =
        new QRCodeWriter().encode(paymentUrl(token, request), BarcodeFormat.QR_CODE, 320, 320);
    var image =
        new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < matrix.getHeight(); y++)
      for (int x = 0; x < matrix.getWidth(); x++)
        image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xffffff);
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(output.toByteArray());
  }

  private String paymentUrl(String token, HttpServletRequest request) {
    String base = publicBaseUrl;
    if (!base.isEmpty()) {
      URI uri = URI.create(base);
      BookingService.require(
          ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
              && uri.getHost() != null
              && uri.getUserInfo() == null
              && uri.getQuery() == null
              && uri.getFragment() == null,
          "Địa chỉ QR demo phải là URL http/https hợp lệ.");
    } else {
      var builder =
          ServletUriComponentsBuilder.fromRequestUri(request).replacePath(request.getContextPath());
      // A phone cannot reach the computer through localhost. Prefer a physical LAN adapter.
      if (Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(request.getServerName())) {
        String lan = lanAddress();
        if (lan != null) builder.host(lan);
      }
      base = builder.build().toUriString();
    }
    return base + "/payment/demo/" + token;
  }

  private String lanAddress() {
    try {
      for (var adapter : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!adapter.isUp() || adapter.isLoopback() || adapter.isVirtual()) continue;
        for (var address : Collections.list(adapter.getInetAddresses()))
          if (address instanceof Inet4Address && address.isSiteLocalAddress())
            return address.getHostAddress();
      }
    } catch (java.net.SocketException ignored) {
      /* The local link remains usable on this computer. */
    }
    return null;
  }
}
