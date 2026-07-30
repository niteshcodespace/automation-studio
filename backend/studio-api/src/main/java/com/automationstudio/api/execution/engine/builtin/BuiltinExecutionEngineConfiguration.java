package com.automationstudio.api.execution.engine.builtin;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.security.SensitiveKeyDetector;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class BuiltinExecutionEngineConfiguration {

    static final int MAX_MESSAGE_LENGTH = 500;
    private static final Set<String> ALLOWED_FIELDS =
            Set.of("operation", "message", "evidence");
    private static final Set<String> ALLOWED_EVIDENCE_FIELDS = Set.of("enabled");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)(password|secret|token|api[_-]?key|private[_-]?key|credential)\\s*[:=]");

    private final SensitiveKeyDetector sensitiveKeyDetector;

    public BuiltinExecutionEngineConfiguration(SensitiveKeyDetector sensitiveKeyDetector) {
        this.sensitiveKeyDetector = sensitiveKeyDetector;
    }

    public Parsed parse(ExecutionContext context) {
        Objects.requireNonNull(context, "Execution context must not be null");
        Map<String, Object> configuration = context.suite().configuration();
        rejectUnknownOrSensitiveKeys(configuration, ALLOWED_FIELDS, "Built-in configuration");

        Object operationValue = configuration.get("operation");
        if (!(operationValue instanceof String operationText) || operationText.isBlank()) {
            throw invalid("Built-in operation is missing or invalid");
        }
        BuiltinExecutionOperation operation;
        try {
            operation = BuiltinExecutionOperation.valueOf(
                    operationText.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("Built-in operation is not supported");
        }

        String message = parseMessage(configuration.get("message"));
        boolean evidenceEnabled = parseEvidence(configuration.get("evidence"));
        return new Parsed(operation, message, evidenceEnabled);
    }

    private String parseMessage(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String message) || message.isBlank()) {
            throw invalid("Built-in message must be a nonblank string");
        }
        String normalized = message.trim();
        if (normalized.length() > MAX_MESSAGE_LENGTH) {
            throw invalid("Built-in message must not exceed " + MAX_MESSAGE_LENGTH + " characters");
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Built-in message must not contain control characters");
        }
        if (SENSITIVE_ASSIGNMENT.matcher(normalized).find()) {
            throw invalid("Built-in message must not contain sensitive assignments");
        }
        return normalized;
    }

    private boolean parseEvidence(Object value) {
        if (value == null) {
            return false;
        }
        if (!(value instanceof Map<?, ?> evidence)) {
            throw invalid("Built-in evidence configuration must be an object");
        }
        for (Map.Entry<?, ?> entry : evidence.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw invalid("Built-in evidence configuration contains an invalid key");
            }
            if (sensitiveKeyDetector.isSensitive(key)
                    || !ALLOWED_EVIDENCE_FIELDS.contains(key)) {
                throw invalid("Built-in evidence configuration contains an unsupported key");
            }
        }
        Object enabled = evidence.get("enabled");
        if (enabled == null) {
            return false;
        }
        if (!(enabled instanceof Boolean flag)) {
            throw invalid("Built-in evidence enabled flag must be boolean");
        }
        return flag;
    }

    private void rejectUnknownOrSensitiveKeys(
            Map<String, Object> values, Set<String> allowed, String owner) {
        for (String key : values.keySet()) {
            if (key == null
                    || key.isBlank()
                    || sensitiveKeyDetector.isSensitive(key)
                    || !allowed.contains(key)) {
                throw invalid(owner + " contains an unsupported key");
            }
        }
    }

    private static BuiltinExecutionEngineException invalid(String message) {
        return new BuiltinExecutionEngineException(message);
    }

    public record Parsed(
            BuiltinExecutionOperation operation,
            String message,
            boolean evidenceEnabled) {

        public Parsed {
            operation = Objects.requireNonNull(operation, "Operation must not be null");
        }
    }
}
