package vn.edu.giavien;

import java.sql.*;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Integration tests only connect to the disposable PostgreSQL database created by the test script. */
abstract class DatabaseIntegrationTest {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws SQLException {
    String isolated = isolatedPostgresUrl();
    registry.add("spring.datasource.url", () -> isolated);
    registry.add("spring.datasource.username", () -> "giavien");
    registry.add("spring.datasource.password", () -> System.getenv("TEST_POSTGRES_PASSWORD"));
  }

  static String isolatedPostgresUrl() throws SQLException {
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
    String url = System.getenv("TEST_POSTGRES_URL");
    validateTestUrl(url);
    String password = System.getenv("TEST_POSTGRES_PASSWORD");
    if (password == null || password.isBlank()) {
      throw new IllegalArgumentException("TEST_POSTGRES_PASSWORD is required. Run ./run.ps1 -Test.");
    }
    String schema = "test_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url, "giavien", password);
        var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + schema);
    }
    return url + "?currentSchema=" + schema;
  }

  static void validateTestUrl(String url) {
    if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:15432/giavien_test_[a-f0-9]{32}")) {
      throw new IllegalArgumentException(
          "Tests require a dedicated giavien_test_<UUID> database. Run ./run.ps1 -Test; never use giavien.");
    }
  }
}
