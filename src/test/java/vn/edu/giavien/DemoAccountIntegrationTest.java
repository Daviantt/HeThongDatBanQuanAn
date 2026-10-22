package vn.edu.giavien;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.giavien.config.SeedData;
import vn.edu.giavien.data.RestaurantRepository;
import vn.edu.giavien.domain.Models.Account;
import vn.edu.giavien.service.BookingService;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:giavien-demo-accounts-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
      "app.demo=true",
      "app.vnpay.tmn-code=",
      "app.vnpay.hash-secret="
    })
@AutoConfigureMockMvc
@Transactional
class DemoAccountIntegrationTest {
  private static final String PASSWORD = "GiaVienDemo123!";
  private static final String LEGACY_PASSWORD = "MocDemo123!";
  private static final String MIGRATION = "giavien-demo-accounts-v1";
  private static final List<DemoAccount> DEMO_ACCOUNTS =
      List.of(
          new DemoAccount("khach@moc.local", "khach@giavien.local", "CUSTOMER"),
          new DemoAccount("nhanvien@moc.local", "nhanvien@giavien.local", "STAFF"),
          new DemoAccount("admin@moc.local", "quanly@giavien.local", "ADMIN"));

  @Autowired RestaurantRepository repo;
  @Autowired BookingService bookings;
  @Autowired SeedData seed;
  @Autowired PasswordEncoder passwords;
  @Autowired MockMvc mvc;

  private record DemoAccount(String legacyEmail, String email, String role) {}

  @Test
  void freshDemoAccountsCanLogInWithTheirDocumentedRolesAndPermissions() throws Exception {
    assertThat(repo.accounts()).hasSize(3);
    for (DemoAccount demo : DEMO_ACCOUNTS) {
      Account account = repo.account(demo.email()).orElseThrow();
      assertThat(account.role()).isEqualTo(demo.role());
      assertThat(passwords.matches(PASSWORD, account.passwordHash())).isTrue();
      assertThat(repo.account(demo.legacyEmail())).isEmpty();

      MockHttpSession session =
          (MockHttpSession)
              mvc.perform(
                      post("/login")
                          .with(csrf())
                          .param("email", demo.email())
                          .param("password", PASSWORD))
                  .andExpect(redirectedUrl(demo.role().equals("CUSTOMER") ? "/book" : "/staff"))
                  .andExpect(authenticated().withUsername(demo.email()).withRoles(demo.role()))
                  .andReturn()
                  .getRequest()
                  .getSession(false);
      assertThat(session).isNotNull();
      mvc.perform(get("/book").session(session))
          .andExpect(status().is(demo.role().equals("CUSTOMER") ? 200 : 403));
      mvc.perform(get("/staff").session(session))
          .andExpect(status().is(demo.role().equals("CUSTOMER") ? 403 : 200));
      mvc.perform(get("/admin").session(session))
          .andExpect(status().is(demo.role().equals("ADMIN") ? 200 : 403));
    }

    String loginPage =
        mvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(loginPage)
        .contains("khach@giavien.local", "nhanvien@giavien.local", "quanly@giavien.local", PASSWORD)
        .doesNotContain("@moc.local", LEGACY_PASSWORD);
  }

  @Test
  void legacyAccountsMigrateInPlaceAndKeepBookingOwnershipAndAuditHistory() {
    List<Account> originalAccounts = repo.accounts();
    restoreLegacyAccounts();
    Account legacyCustomer = repo.account("khach@moc.local").orElseThrow();
    var start = bookings.now().plusDays(1).withHour(18).withMinute(0).withSecond(0).withNano(0);
    String bookingId =
        bookings.create(
            legacyCustomer,
            new BookingService.BookingInput(
                start, start.plusHours(2), 2, List.of(1L), Map.of(), ""));
    repo.insertAccount(
        "regular@example.com",
        passwords.encode("PersonalPass123!"),
        "Khách riêng",
        "0912345678",
        "CUSTOMER");
    Account regularAccount = repo.account("regular@example.com").orElseThrow();

    seed.run();

    for (DemoAccount demo : DEMO_ACCOUNTS) {
      Account original =
          originalAccounts.stream()
              .filter(a -> a.email().equals(demo.email()))
              .findFirst()
              .orElseThrow();
      Account migrated = repo.account(demo.email()).orElseThrow();
      assertThat(migrated.id()).isEqualTo(original.id());
      assertThat(migrated.fullName()).isEqualTo(original.fullName());
      assertThat(migrated.phone()).isEqualTo(original.phone());
      assertThat(migrated.role()).isEqualTo(original.role());
      assertThat(passwords.matches(PASSWORD, migrated.passwordHash())).isTrue();
      assertThat(passwords.matches(LEGACY_PASSWORD, migrated.passwordHash())).isFalse();
      assertThat(repo.account(demo.legacyEmail())).isEmpty();
    }
    Account migratedCustomer = repo.account("khach@giavien.local").orElseThrow();
    assertThat(bookings.accessible(bookingId, migratedCustomer).userId())
        .isEqualTo(legacyCustomer.id());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT actor FROM audit_event WHERE reservation_id=?",
                    String.class,
                    bookingId))
        .isEqualTo("khach@moc.local");
    assertThat(repo.account("regular@example.com").orElseThrow()).isEqualTo(regularAccount);
    List<Account> migratedAccounts = repo.accounts();
    seed.run();
    assertThat(repo.accounts()).isEqualTo(migratedAccounts);
  }

  @Test
  void migrationPreservesCustomizedPasswordNameAndPhoneAcrossRestarts() {
    restoreLegacyAccounts();
    String customHash = passwords.encode("MyCustomPass123!");
    repo.jdbc()
        .update(
            "UPDATE app_user SET password_hash=?,full_name=?,phone=? WHERE email=?",
            customHash,
            "Khách đã đổi thông tin",
            "0987654321",
            "khach@moc.local");
    long originalId = repo.account("khach@moc.local").orElseThrow().id();

    seed.run();
    seed.run();

    Account account = repo.account("khach@giavien.local").orElseThrow();
    assertThat(account.id()).isEqualTo(originalId);
    assertThat(account.passwordHash()).isEqualTo(customHash);
    assertThat(account.fullName()).isEqualTo("Khách đã đổi thông tin");
    assertThat(account.phone()).isEqualTo("0987654321");
    assertThat(passwords.matches(PASSWORD, account.passwordHash())).isFalse();
  }

  @Test
  void occupiedNewAddressDoesNotOverwriteOrPromoteEitherAccount() {
    repo.jdbc()
        .update(
            "UPDATE app_user SET role='CUSTOMER',password_hash=?,full_name=? WHERE email=?",
            passwords.encode("PersonalPass123!"),
            "Tài khoản đã tồn tại",
            "quanly@giavien.local");
    repo.insertAccount(
        "admin@moc.local", passwords.encode(LEGACY_PASSWORD), "Quản lý cũ", "0901234569", "ADMIN");
    allowLegacyMigration();
    List<Account> originalAccounts = repo.accounts();

    seed.run();
    seed.run();

    assertThat(repo.accounts()).isEqualTo(originalAccounts);
  }

  @Test
  void unexpectedLegacyRoleIsPreservedAndNeverPromoted() {
    repo.jdbc()
        .update(
            "UPDATE app_user SET email='admin@moc.local',role='CUSTOMER' WHERE"
                + " email='quanly@giavien.local'");
    Account existingCustomer = repo.account("admin@moc.local").orElseThrow();
    allowLegacyMigration();

    seed.run();

    assertThat(repo.account("admin@moc.local").orElseThrow()).isEqualTo(existingCustomer);
    Account newAdmin = repo.account("quanly@giavien.local").orElseThrow();
    assertThat(newAdmin.id()).isNotEqualTo(existingCustomer.id());
    assertThat(newAdmin.role()).isEqualTo("ADMIN");
    assertThat(passwords.matches(PASSWORD, newAdmin.passwordHash())).isTrue();
  }

  @Test
  void nonDemoModeDoesNotCreateOrMigrateAccounts() {
    restoreLegacyAccounts();
    List<Account> originalAccounts = repo.accounts();

    new SeedData(repo, passwords, false).run();

    assertThat(repo.accounts()).isEqualTo(originalAccounts);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT COUNT(*) FROM app_migration WHERE name=?", Integer.class, MIGRATION))
        .isZero();
  }

  @Test
  void completedMigrationDoesNotRenameLegacyAddressesCreatedLater() {
    repo.jdbc().update("DELETE FROM app_user WHERE email='khach@giavien.local'");
    repo.insertAccount(
        "khach@moc.local",
        passwords.encode("PersonalPass123!"),
        "Khách mới",
        "0901234567",
        "CUSTOMER");
    Account laterAccount = repo.account("khach@moc.local").orElseThrow();

    seed.run();

    assertThat(repo.account("khach@moc.local").orElseThrow()).isEqualTo(laterAccount);
    assertThat(repo.account("khach@giavien.local").orElseThrow().id())
        .isNotEqualTo(laterAccount.id());
  }

  private void restoreLegacyAccounts() {
    for (DemoAccount demo : DEMO_ACCOUNTS) {
      repo.jdbc()
          .update(
              "UPDATE app_user SET email=?,password_hash=? WHERE email=?",
              demo.legacyEmail(),
              passwords.encode(LEGACY_PASSWORD),
              demo.email());
    }
    allowLegacyMigration();
  }

  private void allowLegacyMigration() {
    repo.jdbc().update("DELETE FROM app_migration WHERE name=?", MIGRATION);
  }
}
