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
                        "/payment/demo/**",
                        "/takeaway/payment/**",
                        "/payment/vnpay/**")
                    .permitAll()
                    .requestMatchers("/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/staff/**")
                    .hasAnyRole("STAFF", "ADMIN")
                    .requestMatchers("/book", "/bookings", "/takeaway", "/api/takeaway-orders", "/api/takeaway-orders/quote")
                    .hasRole("CUSTOMER")
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/bookings", "/bookings/**", "/takeaway/orders/**")
                    .hasRole("CUSTOMER")
                    .anyRequest()
                    .authenticated())
        .formLogin(
            login ->
                login
                    .loginPage("/login")
                    .usernameParameter("email")
                    .successHandler((request, response, authentication) -> {
                      boolean employee = authentication.getAuthorities().stream().anyMatch(a ->
                          a.getAuthority().equals("ROLE_STAFF") || a.getAuthority().equals("ROLE_ADMIN"));
                      var handler = new org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler();
                      handler.setDefaultTargetUrl(employee ? "/staff" : "/book");
                      handler.setAlwaysUseDefaultTargetUrl(employee);
                      handler.onAuthenticationSuccess(request, response, authentication);
                    })
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
