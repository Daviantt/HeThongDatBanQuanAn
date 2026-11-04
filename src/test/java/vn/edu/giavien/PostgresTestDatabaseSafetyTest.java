package vn.edu.giavien;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PostgresTestDatabaseSafetyTest {
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {
      "jdbc:postgresql://127.0.0.1:15432/giavien",
      "jdbc:postgresql://127.0.0.1:15432/giavien_test_",
      "jdbc:postgresql://127.0.0.1:15432/giavien_test_0123456789abcdef0123456789abcdef?currentSchema=public"
  })
  void refusesMissingOrNonIsolatedDatabaseBeforeAnyConnection(String url) {
    assertThatThrownBy(() -> DatabaseIntegrationTest.validateTestUrl(url))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Run ./run.ps1 -Test");
  }

  @Test
  void acceptsOnlyTheDedicatedTestDatabaseUrl() {
    assertThatCode(() -> DatabaseIntegrationTest.validateTestUrl(
        "jdbc:postgresql://127.0.0.1:15432/giavien_test_0123456789abcdef0123456789abcdef"))
        .doesNotThrowAnyException();
  }
}
