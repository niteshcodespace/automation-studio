package com.automationstudio.api.execution.orchestration;

import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.SourceConfigurationValidator;
import com.automationstudio.api.source.SourceType;
import java.util.Map;
import java.util.Set;

public final class AdmittedSourceSnapshotMapper {

    private static final Set<String> FIELDS =
            Set.of("sourceType", "repository", "revision", "sourceLocation");

    private final SourceConfigurationValidator validator;

    public AdmittedSourceSnapshotMapper(SourceConfigurationValidator validator) {
        this.validator = java.util.Objects.requireNonNull(
                validator, "Source configuration validator must not be null");
    }

    public ExecutionSourceReference map(Map<String, Object> snapshot) {
        try {
            if (snapshot == null || !snapshot.keySet().equals(FIELDS)) {
                throw new IllegalArgumentException();
            }
            return validator.validate(
                    SourceType.valueOf(text(snapshot, "sourceType", false)),
                    text(snapshot, "repository", false),
                    text(snapshot, "revision", false),
                    text(snapshot, "sourceLocation", true));
        } catch (RuntimeException failure) {
            throw new RunnerPipelineException(
                    "ADMITTED_SOURCE_INVALID",
                    "Admitted execution source is invalid");
        }
    }

    private static String text(
            Map<String, Object> snapshot, String field, boolean nullable) {
        Object value = snapshot.get(field);
        if (nullable && value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException();
        }
        return text;
    }
}
