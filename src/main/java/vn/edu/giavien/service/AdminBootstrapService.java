package vn.edu.giavien.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.giavien.data.RestaurantRepository;

/** Local startup operation only; deliberately has no HTTP controller. */
@Service
public class AdminBootstrapService {
  private final RestaurantRepository repo;
  private final PasswordEncoder passwords;

  public AdminBootstrapService(RestaurantRepository repo, PasswordEncoder passwords) {
    this.repo = repo;
    this.passwords = passwords;
  }

  @Transactional
  public void initialize(String configuredEmail, String password) {
    repo.lockSchedule();
    String email = configuredEmail == null ? "" : configuredEmail.strip().toLowerCase(Locale.ROOT);
    var existing = email.isEmpty() ? java.util.Optional.<vn.edu.giavien.domain.Models.Account>empty() : repo.account(email);
    if (existing.isPresent() && !existing.get().role().equals("ADMIN")) {
      throw new IllegalStateException("BOOTSTRAP_ADMIN_EMAIL already belongs to a non-admin account; choose another email.");
    }
    if (repo.jdbc().queryForObject("SELECT COUNT(*) FROM app_user WHERE role='ADMIN'", Integer.class) > 0) return;
    if (email.length() > 180 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
        || password == null || password.isBlank() || password.length() < 12
        || password.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new IllegalStateException("No administrator exists. Set BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD (12+ characters, at most 72 UTF-8 bytes), then restart. Do not enable demo in production.");
    }
    repo.jdbc().update("INSERT INTO app_user(email,password_hash,full_name,phone,role) VALUES(?,?,?,?, 'ADMIN')",
        email, passwords.encode(password), "Quản trị viên", "");
  }
}
