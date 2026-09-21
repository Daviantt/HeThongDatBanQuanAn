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
    var application = new SpringApplication(GiaVienApplication.class);
    // Existing installations keep their database; fresh installations use the new brand.
    String database =
        java.nio.file.Files.exists(java.nio.file.Path.of("data/moc.mv.db"))
                && !java.nio.file.Files.exists(java.nio.file.Path.of("data/giavien.mv.db"))
            ? "moc"
            : "giavien";
    application.setDefaultProperties(java.util.Map.of("app.database-name", database));
    application.run(args);
  }

  @Bean
  Clock clock() {
    return Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));
  }
}
