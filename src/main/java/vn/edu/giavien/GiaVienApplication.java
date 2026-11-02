package vn.edu.giavien;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GiaVienApplication {
  public static void main(String[] args) {
    // pgJDBC sends the JVM zone at connection time; use the canonical IANA name.
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
    SpringApplication.run(GiaVienApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));
  }
}
