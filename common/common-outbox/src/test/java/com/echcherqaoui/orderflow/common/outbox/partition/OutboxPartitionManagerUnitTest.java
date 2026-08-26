package com.echcherqaoui.orderflow.common.outbox.partition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OutboxPartitionManagerUnitTest {

    private final OutboxPartitionManager manager = new OutboxPartitionManager(mock(JdbcTemplate.class), 7, 2);

    @ParameterizedTest
    @ValueSource(strings = {"outbox_p2026_01_01", "outbox_p_test", "valid_table_name"})
    @DisplayName("sanitizeIdentifier allows valid SQL table identifiers")
    void sanitizeIdentifier_validInputs_returnsIdentifier(String validIdentifier) throws Exception {
        Method method = OutboxPartitionManager.class.getDeclaredMethod("sanitizeIdentifier", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(manager, validIdentifier);

        assertThat(result).isEqualTo(validIdentifier);
    }

    @ParameterizedTest
    @ValueSource(strings = {
          "outbox_p;",
          "DROP TABLE users;",
          "outbox-p",
          "123table",
          "outbox table",
          "outbox'--"
    })
    @DisplayName("sanitizeIdentifier throws IllegalArgumentException on invalid identifiers and SQL injection")
    void sanitizeIdentifier_invalidInputs_throwsException(String invalidIdentifier) {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(manager, "sanitizeIdentifier", invalidIdentifier))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("Invalid SQL identifier");
    }
}