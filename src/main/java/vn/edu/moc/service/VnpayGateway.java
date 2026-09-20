package vn.edu.moc.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.edu.moc.domain.Models.Booking;

@Service
public class VnpayGateway {
  private final String tmnCode, secret, returnUrl;

  public VnpayGateway(
      @Value("${app.vnpay.tmn-code}") String tmnCode,
      @Value("${app.vnpay.hash-secret}") String secret,
      @Value("${app.vnpay.return-url}") String returnUrl) {
    this.tmnCode = tmnCode;
    this.secret = secret;
    this.returnUrl = returnUrl;
  }

  public boolean configured() {
    return !tmnCode.isBlank() && !secret.isBlank();
  }

  public String paymentUrl(Booking booking, String ip) {
    BookingService.require(
        configured(), "VNPAY sandbox chưa được cấu hình. Vui lòng liên hệ quản lý.");
    var format = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    Map<String, String> p = new TreeMap<>();
    p.put("vnp_Version", "2.1.0");
    p.put("vnp_Command", "pay");
    p.put("vnp_TmnCode", tmnCode);
    p.put("vnp_Amount", Long.toString(booking.deposit() * 100));
    p.put("vnp_CurrCode", "VND");
    p.put("vnp_TxnRef", booking.id());
    p.put("vnp_OrderInfo", "Dat coc ban " + booking.code());
    p.put("vnp_OrderType", "other");
    p.put("vnp_Locale", "vn");
    p.put("vnp_ReturnUrl", returnUrl);
    p.put("vnp_IpAddr", ip);
    p.put("vnp_CreateDate", booking.createdAt().format(format));
    p.put("vnp_ExpireDate", booking.holdUntil().format(format));
    String query = canonical(p);
    return "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?"
        + query
        + "&vnp_SecureHash="
        + sign(query);
  }

  public boolean verified(Map<String, String> parameters) {
    if (!configured() || !tmnCode.equals(parameters.get("vnp_TmnCode"))) return false;
    String received = parameters.getOrDefault("vnp_SecureHash", "");
    if (!received.matches("[a-fA-F0-9]{128}")) return false;
    Map<String, String> signed = new TreeMap<>();
    parameters.forEach(
        (key, value) -> {
          if (key.startsWith("vnp_")
              && !key.equals("vnp_SecureHash")
              && !key.equals("vnp_SecureHashType")
              && value != null
              && !value.isEmpty()) signed.put(key, value);
        });
    return MessageDigest.isEqual(
        sign(canonical(signed)).getBytes(StandardCharsets.US_ASCII),
        received.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
  }

  private static String canonical(Map<String, String> values) {
    return new TreeMap<>(values)
        .entrySet().stream()
            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(Collectors.joining("&"));
  }

  private static String encode(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8);
  }

  private String sign(String text) {
    try {
      Mac mac = Mac.getInstance("HmacSHA512");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
      return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Không thể tạo chữ ký thanh toán", e);
    }
  }
}
