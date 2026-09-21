package vn.edu.giavien.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import vn.edu.giavien.data.RestaurantRepository;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(RestaurantRepository repo) {
    return email ->
        repo.account(email)
            .map(
                a ->
                    User.withUsername(a.email()).password(a.passwordHash()).roles(a.role()).build())
            .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy tài khoản"));
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/",
                        "/menu",
                        "/login",
                        "/register",
                        "/css/**",
                        "/js/**",
                        "/images/**",
                        "/error",
                        "/payment/vnpay/**")
                    .permitAll()
                    .requestMatchers("/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/staff/**")
                    .hasAnyRole("STAFF", "ADMIN")
                    .anyRequest()
                    .authenticated())
        .formLogin(
            login ->
                login
                    .loginPage("/login")
                    .usernameParameter("email")
                    .defaultSuccessUrl("/book", false)
                    .permitAll())
        .logout(logout -> logout.logoutSuccessUrl("/"))
        .exceptionHandling(
            handler ->
                handler.authenticationEntryPoint(
                    (request, response, error) -> {
                      if (request.getRequestURI().startsWith("/api/")) {
                        response.setStatus(401);
                        response.setContentType("application/json;charset=UTF-8");
                        response
                            .getWriter()
                            .write(
                                "{\"error\":\"Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập"
                                    + " lại.\"}");
                      } else response.sendRedirect("/login");
                    }));
    return http.build();
  }
}
