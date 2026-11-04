package vn.edu.giavien;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.giavien.data.RestaurantRepository;
import vn.edu.giavien.service.AdminBootstrapService;
import vn.edu.giavien.service.DemoPaymentService;

@SpringBootTest(properties = {"app.demo=false", "app.payment.demo-enabled=false",
    "BOOTSTRAP_ADMIN_EMAIL=bootstrap@example.test", "BOOTSTRAP_ADMIN_PASSWORD=TestOnlyPassword123!"})
@AutoConfigureMockMvc
class AdminBootstrapIntegrationTest extends DatabaseIntegrationTest {
  @Autowired AdminBootstrapService bootstrap;
  @Autowired RestaurantRepository repo;
  @Autowired PasswordEncoder passwords;
  @Autowired DemoPaymentService payments;
  @Autowired MockMvc mvc;
  static final String EMAIL = "owner@example.test";
  static final String PASSWORD = "TestOnlyPassword123!";

  @BeforeEach void reset() { repo.jdbc().update("DELETE FROM app_user"); }

  @Test void createsHashedAdminOutsideDemoAndCanLogIn() throws Exception {
    bootstrap.initialize(" OWNER@example.test ", PASSWORD);
    var admin = repo.account(EMAIL).orElseThrow();
    assertThat(admin.role()).isEqualTo("ADMIN");
    assertThat(admin.passwordHash()).isNotEqualTo(PASSWORD);
    assertThat(passwords.matches(PASSWORD, admin.passwordHash())).isTrue();
    assertThat(repo.accounts()).hasSize(1);
    assertThat(payments.enabled()).isFalse();
    mvc.perform(post("/login").with(csrf()).param("email", EMAIL).param("password", PASSWORD))
        .andExpect(authenticated().withRoles("ADMIN"));
    mvc.perform(get("/admin").with(user(EMAIL).roles("ADMIN"))).andExpect(status().isOk());
    mvc.perform(get("/admin").with(user("customer@example.test").roles("CUSTOMER")))
        .andExpect(status().isForbidden());
  }

  @Test void existingAdminAndImportedHashRemainUntouchedWithoutSecrets() {
    bootstrap.initialize(EMAIL, PASSWORD);
    var before = repo.account(EMAIL).orElseThrow();
    bootstrap.initialize("", "");
    bootstrap.initialize(EMAIL, "AReplacementPassword123!");
    bootstrap.initialize("different@example.test", PASSWORD);
    assertThat(repo.accounts()).containsExactly(before);
  }

  @Test void missingOrInvalidConfigurationDoesNotCreateAnyAccount() {
    assertThatThrownBy(() -> bootstrap.initialize("", "")).hasMessageContaining("BOOTSTRAP_ADMIN_PASSWORD");
    assertThatThrownBy(() -> bootstrap.initialize(EMAIL, "short")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> bootstrap.initialize(EMAIL, "ậ".repeat(25))).isInstanceOf(IllegalStateException.class);
    assertThat(repo.accounts()).isEmpty();
  }

  @Test void refusesToPromoteExistingCustomerOrStaffEvenWhenAdminExists() {
    for (String role : new String[] {"CUSTOMER", "STAFF"}) {
      repo.jdbc().update("DELETE FROM app_user");
      repo.jdbc().update("INSERT INTO app_user(email,password_hash,full_name,phone,role) VALUES(?,?,?,?,?)",
          EMAIL, passwords.encode(PASSWORD), "Existing", "0901234567", role);
      var before = repo.account(EMAIL).orElseThrow();
      assertThatThrownBy(() -> bootstrap.initialize(EMAIL, PASSWORD)).hasMessageContaining("non-admin");
      bootstrap.initialize("another@example.test", PASSWORD);
      assertThatThrownBy(() -> bootstrap.initialize(EMAIL, PASSWORD)).hasMessageContaining("non-admin");
      assertThat(repo.account(EMAIL).orElseThrow()).isEqualTo(before);
    }
  }

  @Test void concurrentBootstrapCreatesOnlyOneAdministrator() throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var first = pool.submit(() -> { start.await(); bootstrap.initialize(EMAIL, PASSWORD); return true; });
      var second = pool.submit(() -> { start.await(); bootstrap.initialize("other@example.test", PASSWORD); return true; });
      start.countDown();
      assertThat(first.get(15, TimeUnit.SECONDS)).isTrue();
      assertThat(second.get(15, TimeUnit.SECONDS)).isTrue();
      assertThat(repo.accounts()).hasSize(1);
    }
  }
}
