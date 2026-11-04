package vn.edu.giavien;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** No Spring context: exercise old rows through schema upgrade and legacy fixture import. */
class SchemaUpgradeIntegrationTest {
  @Test void concurrentFreshSchemaInitializationIsSerialized() throws Exception {
    String url = DatabaseIntegrationTest.isolatedPostgresUrl();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Boolean> initialize = () -> {
        start.await();
        try (var pg = DriverManager.getConnection(url, "giavien", System.getenv("TEST_POSTGRES_PASSWORD"))) {
          ScriptUtils.executeSqlScript(pg, new ClassPathResource("schema.sql"));
        }
        return true;
      };
      var first = pool.submit(initialize);
      var second = pool.submit(initialize);
      start.countDown();
      assertThat(first.get(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(second.get(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
    try (var pg = DriverManager.getConnection(url, "giavien", System.getenv("TEST_POSTGRES_PASSWORD"));
        var sql = pg.createStatement(); var rows = sql.executeQuery("SELECT COUNT(*) FROM app_lock")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isEqualTo(1);
    }
  }

  @Test void upgradePreservesLegacyRowsAndNeverInventsStockDeductions() throws Exception {
    String url = DatabaseIntegrationTest.isolatedPostgresUrl();
    try (var pg = DriverManager.getConnection(url, "giavien", System.getenv("TEST_POSTGRES_PASSWORD"));
        var sql = pg.createStatement()) {
      ScriptUtils.executeSqlScript(pg, new ClassPathResource("schema.sql"));
      sql.execute("ALTER TABLE takeaway_order_line DROP COLUMN stock_deducted, DROP COLUMN stock_returned");
      // Real H2 source fixture; it is never used as an integration-test target.
      try (var h2 = DriverManager.getConnection("jdbc:h2:mem:legacy_" + UUID.randomUUID());
          var legacy = h2.createStatement()) {
        legacy.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY,email VARCHAR(180),password_hash VARCHAR(100),full_name VARCHAR(100),phone VARCHAR(20),role VARCHAR(20))");
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("LegacyFixturePassword123!");
        try (var insert = h2.prepareStatement("INSERT INTO app_user VALUES(42,'legacy@example.test',?,'Khách cũ','0901234567','CUSTOMER')")) {
          insert.setString(1, hash);
          insert.executeUpdate();
        }
        try (var rows = legacy.executeQuery("SELECT * FROM app_user");
            var insert = pg.prepareStatement("INSERT INTO app_user(id,email,password_hash,full_name,phone,role) VALUES(?,?,?,?,?,?)")) {
          while (rows.next()) {
            for (int column = 1; column <= 6; column++) insert.setObject(column, rows.getObject(column));
            insert.executeUpdate();
          }
        }
        sql.execute("SELECT setval(pg_get_serial_sequence('app_user','id'),(SELECT MAX(id) FROM app_user),true)");
        sql.execute("INSERT INTO menu_item(id,name,description,category,price,available,illustration,stock_quantity) VALUES(55,'Món cũ','','Món chính',10000,TRUE,'rice',8)");
        sql.execute("INSERT INTO takeaway_order(id,user_id,customer_name,customer_phone,pickup_at,total,payment_method,status,created_at) VALUES('legacy-order',42,'Khách cũ','0901234567','2026-09-21 11:00',20000,'CASH','WAITING_PICKUP','2026-09-21 10:00')");
        sql.execute("INSERT INTO takeaway_order_line VALUES('legacy-order',55,'Món cũ',10000,2)");
        ScriptUtils.executeSqlScript(pg, new ClassPathResource("schema.sql"));
        ScriptUtils.executeSqlScript(pg, new ClassPathResource("schema.sql"));
        try (var rows = sql.executeQuery("SELECT u.password_hash,o.status,l.stock_deducted,l.stock_returned,m.stock_quantity FROM app_user u JOIN takeaway_order o ON u.id=o.user_id JOIN takeaway_order_line l ON l.order_id=o.id JOIN menu_item m ON m.id=l.menu_item_id")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo(hash);
          assertThat(rows.getString(2)).isEqualTo("WAITING_CONFIRMATION");
          assertThat(rows.getInt(3)).isZero();
          assertThat(rows.getBoolean(4)).isFalse();
          assertThat(rows.getInt(5)).isEqualTo(8);
          assertThat(rows.next()).isFalse();
        }
        try (var rows = sql.executeQuery("INSERT INTO app_user(email,password_hash,full_name,phone,role) VALUES('new@example.test','test','New','','CUSTOMER') RETURNING id")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getLong(1)).isGreaterThan(42);
        }
      }
    }
  }
}
