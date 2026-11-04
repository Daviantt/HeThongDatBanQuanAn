package vn.edu.giavien.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vn.edu.giavien.service.AdminBootstrapService;

@Component
@Order(100)
public class AdminBootstrap implements CommandLineRunner {
  private final AdminBootstrapService service;
  private final boolean demo;
  private final String email;
  private final String password;

  public AdminBootstrap(AdminBootstrapService service, @Value("${app.demo:false}") boolean demo,
      @Value("${BOOTSTRAP_ADMIN_EMAIL:}") String email,
      @Value("${BOOTSTRAP_ADMIN_PASSWORD:}") String password) {
    this.service = service;
    this.demo = demo;
    this.email = email;
    this.password = password;
  }

  @Override
  public void run(String... args) {
    if (!demo) service.initialize(email, password);
  }
}
