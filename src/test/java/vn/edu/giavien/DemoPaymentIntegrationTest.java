package vn.edu.giavien;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.giavien.data.RestaurantRepository;
import vn.edu.giavien.domain.Models.*;
import vn.edu.giavien.service.*;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:giavien-demo-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
      "app.demo=true",
      "app.payment.demo-enabled=true",
      "app.vnpay.tmn-code=",
      "app.vnpay.hash-secret=",
      "app.payment.demo-base-url=http://192.168.1.20:8080"
    })
@AutoConfigureMockMvc
@Import(BookingIntegrationTest.TimeConfig.class)
class DemoPaymentIntegrationTest {
  @Autowired BookingService bookings;
  @Autowired DemoPaymentService payments;
  @Autowired VnpayGateway gateway;
  @Autowired RestaurantRepository repo;
  @Autowired BookingIntegrationTest.MutableClock clock;
  @Autowired MockMvc mvc;
  final LocalDateTime baseline = LocalDateTime.of(2026, 9, 21, 10, 0);
  Account customer;
  String bookingId;

  @BeforeEach
  void setup() {
    clock.set(baseline);
    for (String table :
        List.of(
            "audit_event",
            "demo_payment",
            "payment_attempt",
            "change_request",
            "order_line",
            "reservation_table",
            "reservation")) repo.jdbc().update("DELETE FROM " + table);
    customer = bookings.account("khach@moc.local");
    bookingId =
        bookings.create(
            customer,
            new BookingService.BookingInput(
                baseline.plusDays(1).withHour(18),
                baseline.plusDays(1).withHour(20),
                2,
                List.of(1L),
                Map.of(),
                ""));
  }

  @Test
  void minuteIsEnforcedByServerAndDoesNotResetOnReload() throws Exception {
    String token = payments.start(bookingId, customer);
    assertThat(payments.status(token).remainingSeconds()).isEqualTo(60);
    assertThatThrownBy(() -> payments.confirm(token)).hasMessageContaining("1 phút");
    clock.set(baseline.plusSeconds(59));
    assertThat(payments.start(bookingId, customer)).isEqualTo(token);
    assertThat(payments.status(token).remainingSeconds()).isEqualTo(1);
    mvc.perform(post("/payment/demo/" + token + "/confirm").with(csrf()))
        .andExpect(status().is3xxRedirection());
    assertThat(repo.booking(bookingId).orElseThrow().paymentStatus())
        .isEqualTo(PaymentStatus.UNPAID);
    clock.set(baseline.plusSeconds(60));
    assertThat(payments.status(token).status()).isEqualTo("READY");
    assertThat(repo.booking(bookingId).orElseThrow().paymentStatus())
        .isEqualTo(PaymentStatus.UNPAID);
    mvc.perform(post("/payment/demo/" + token + "/confirm").with(csrf()))
        .andExpect(redirectedUrl("/payment/demo/" + token));
    var booking = repo.booking(bookingId).orElseThrow();
    assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(booking.paymentStatus()).isEqualTo(PaymentStatus.PAID);
    assertThat(payments.isDemoPayment(bookingId)).isTrue();
    assertThat(payments.status(token).status()).isEqualTo("PAID");
    mvc.perform(get("/bookings/" + bookingId).with(user(customer.email())))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("mô phỏng demo")));
  }

  @Test
  void scanPageIsPublicAndQrDecodesToItsOwnSessionWithoutExposingCustomer() throws Exception {
    var start =
        mvc.perform(
                post("/bookings/" + bookingId + "/demo-payment")
                    .with(user(customer.email()))
                    .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    String path = start.getResponse().getRedirectedUrl();
    mvc.perform(get(path))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("QR đặt cọc demo")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(customer.email()))));
    byte[] bytes =
        mvc.perform(get(path + "/qr.png"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    var image = ImageIO.read(new ByteArrayInputStream(bytes));
    var source =
        new RGBLuminanceSource(
            image.getWidth(),
            image.getHeight(),
            image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
    String decoded =
        new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    assertThat(decoded).isEqualTo("http://192.168.1.20:8080" + path);
    mvc.perform(get(path + "/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("WAITING"));
    assertThat(repo.booking(bookingId).orElseThrow().paymentStatus())
        .isEqualTo(PaymentStatus.UNPAID);
  }

  @Test
  void ownerCsrfTokenAndModeAreRequired() throws Exception {
    mvc.perform(post("/bookings/" + bookingId + "/demo-payment").with(csrf()))
        .andExpect(status().is3xxRedirection());
    mvc.perform(post("/bookings/" + bookingId + "/demo-payment").with(user(customer.email())))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/bookings/" + bookingId + "/demo-payment")
                .with(user("someone@example.test"))
                .with(csrf()))
        .andExpect(status().isForbidden());
    assertThatThrownBy(() -> payments.start(bookingId, bookings.account("admin@moc.local")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    String token = payments.start(bookingId, customer);
    clock.set(baseline.plusMinutes(1));
    mvc.perform(post("/payment/demo/" + token + "/confirm")).andExpect(status().isForbidden());
    mvc.perform(get("/payment/demo/" + "0".repeat(32))).andExpect(status().isNotFound());
    mvc.perform(post("/payment/demo/" + "0".repeat(32) + "/confirm").with(csrf()))
        .andExpect(status().isNotFound());
    var off = new DemoPaymentService(repo, bookings, gateway, false);
    assertThatThrownBy(() -> off.confirm(token))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    var configured =
        new VnpayGateway("TESTCODE", "fake-secret", "http://localhost:8080/payment/vnpay/return");
    var sandbox = new DemoPaymentService(repo, bookings, configured, true);
    assertThat(sandbox.enabled()).isFalse();
    assertThatThrownBy(() -> sandbox.start(bookingId, customer))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  @Test
  void canceledAndExpiredHoldsCannotBeConfirmed() {
    String token = payments.start(bookingId, customer);
    clock.set(baseline.plusMinutes(1));
    bookings.cancel(bookingId, customer, false);
    assertThat(payments.status(token).status()).isEqualTo("CLOSED");
    assertThatThrownBy(() -> payments.confirm(token)).hasMessageContaining("hết hạn");
    assertThat(repo.booking(bookingId).orElseThrow().paymentStatus())
        .isEqualTo(PaymentStatus.UNPAID);
    String other =
        bookings.create(
            customer,
            new BookingService.BookingInput(
                baseline.plusDays(1).withHour(18),
                baseline.plusDays(1).withHour(20),
                2,
                List.of(1L),
                Map.of(),
                ""));
    String expired = payments.start(other, customer);
    clock.set(baseline.plusMinutes(16));
    assertThatThrownBy(() -> payments.confirm(expired)).hasMessageContaining("hết hạn");
    assertThat(payments.status(expired).status()).isEqualTo("CLOSED");
    assertThat(repo.booking(other).orElseThrow().paymentStatus()).isEqualTo(PaymentStatus.UNPAID);
  }

  @Test
  void simultaneousConfirmationsProduceOneDemoPayment() throws Exception {
    String token = payments.start(bookingId, customer);
    clock.set(baseline.plusMinutes(1));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var ready = new CountDownLatch(1);
      Callable<Void> confirm =
          () -> {
            ready.await();
            payments.confirm(token);
            return null;
          };
      var first = pool.submit(confirm);
      var second = pool.submit(confirm);
      ready.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }
    assertThat(repo.events(bookingId).stream().filter(e -> e.actor().equals("DEMO")).count())
        .isEqualTo(1);
    assertThat(repo.jdbc().queryForObject("SELECT COUNT(*) FROM payment_attempt", Integer.class))
        .isZero();
  }
}
