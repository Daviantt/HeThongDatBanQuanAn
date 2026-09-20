package vn.edu.moc;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MocApplication {
  public static void main(String[] args) {
    SpringApplication.run(MocApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));
  }
}
