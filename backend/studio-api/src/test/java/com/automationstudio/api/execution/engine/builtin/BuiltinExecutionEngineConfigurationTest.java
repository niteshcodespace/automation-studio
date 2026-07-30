package com.automationstudio.api.execution.engine.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.ExecutionSuiteSnapshot;
import com.automationstudio.api.security.SensitiveKeyDetector;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuiltinExecutionEngineConfigurationTest {

    private final BuiltinExecutionEngineConfiguration configuration =
            new BuiltinExecutionEngineConfiguration(new SensitiveKeyDetector());

    @Test
    void parsesAllowlistedConfigurationAndDeterministicDefaults() {
        var parsed = configuration.parse(context(Map.of(
                "operation", "succeed",
                "message", "  approved message  ",
                "evidence", Map.of("enabled", true))));

        assertThat(parsed.operation()).isEqualTo(BuiltinExecutionOperation.SUCCEED);
        assertThat(parsed.message()).isEqualTo("approved message");
        assertThat(parsed.evidenceEnabled()).isTrue();

        var defaults = configuration.parse(context(Map.of("operation", "FAIL")));
        assertThat(defaults.message()).isNull();
        assertThat(defaults.evidenceEnabled()).isFalse();
    }

    @Test
    void rejectsMissingUnsupportedAndUnsafeOperationConfiguration() {
        assertInvalid(Map.of());
        assertInvalid(Map.of("operation", "UNKNOWN"));
        assertInvalid(Map.of("operation", 1));
        assertInvalid(Map.of("operation", "SUCCEED", "unexpected", true));
        assertInvalid(Map.of("operation", "SUCCEED", "claimToken", "redacted"));
        assertInvalid(Map.of("operation", "SUCCEED", "evidence", "true"));
        assertInvalid(Map.of(
                "operation", "SUCCEED", "evidence", Map.of("enabled", "true")));
        assertInvalid(Map.of(
                "operation", "SUCCEED", "evidence", Map.of("bucket", "value")));
    }

    @Test
    void rejectsBlankOversizedControlAndSensitiveMessages() {
        assertInvalid(Map.of("operation", "SUCCEED", "message", " "));
        assertInvalid(Map.of(
                "operation", "SUCCEED", "message", "x".repeat(501)));
        assertInvalid(Map.of(
                "operation", "SUCCEED", "message", "line\nbreak"));
        assertInvalid(Map.of(
                "operation", "SUCCEED", "message", "token=do-not-expose"));
        assertInvalid(Map.of(
                "operation", "SUCCEED", "message", "password: do-not-expose"));
    }

    private void assertInvalid(Map<String, Object> values) {
        assertThatThrownBy(() -> configuration.parse(context(values)))
                .isInstanceOf(BuiltinExecutionEngineException.class);
    }

    private ExecutionContext context(Map<String, Object> values) {
        ExecutionContext context = mock(ExecutionContext.class);
        ExecutionSuiteSnapshot suite = mock(ExecutionSuiteSnapshot.class);
        when(context.suite()).thenReturn(suite);
        when(suite.configuration()).thenReturn(values);
        return context;
    }
}
