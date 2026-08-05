package com.automationstudio.api.execution.engine;

import com.automationstudio.api.execution.ExecutionContext;
import com.automationstudio.api.execution.preparation.SourcePreparationResult;
import com.automationstudio.api.execution.preparation.SourcePreparationState;
import com.automationstudio.api.execution.secret.ExecutionSecretAccess;
import com.automationstudio.api.execution.workspace.WorkspaceMetadata;
import com.automationstudio.api.execution.workspace.WorkspaceState;
import com.automationstudio.api.source.ExecutionSourceReference;
import com.automationstudio.api.source.materialization.SourceMaterializationResult;
import java.util.Objects;
import java.util.UUID;

public record EngineExecutionRequest(
        ExecutionContext context,
        SourcePreparationResult preparation,
        ExecutionSecretAccess secretAccess) {

    public EngineExecutionRequest {
        context = Objects.requireNonNull(context, "Execution context must not be null");
        preparation = Objects.requireNonNull(
                preparation, "Source preparation result must not be null");
        secretAccess = Objects.requireNonNull(
                secretAccess, "Execution secret access must not be null");
        if (preparation.state() != SourcePreparationState.PREPARED) {
            throw new IllegalArgumentException("Source preparation must be complete");
        }
        if (!context.executionId().equals(preparation.executionId())) {
            throw new IllegalArgumentException(
                    "Execution context and preparation must identify the same execution");
        }
        if (!context.executionId().equals(secretAccess.executionId())) {
            throw new IllegalArgumentException(
                    "Execution context and secret access must identify the same execution");
        }
        requireConsistentPreparation(context, preparation);
    }

    public EngineExecutionRequest(
            ExecutionContext context, SourcePreparationResult preparation) {
        this(
                context,
                preparation,
                ExecutionSecretAccess.unavailable(
                        Objects.requireNonNull(
                                context, "Execution context must not be null").executionId()));
    }

    public UUID executionId() {
        return context.executionId();
    }

    public String engineId() {
        return context.suite().engineId();
    }

    public String implementationVersion() {
        return context.suite().engineVersion();
    }

    /**
     * Compatibility alias for callers written before canonical engine ID terminology.
     */
    @Deprecated(forRemoval = false)
    public String engineName() {
        return engineId();
    }

    /**
     * Compatibility alias for callers written before implementation-version terminology.
     */
    @Deprecated(forRemoval = false)
    public String engineVersion() {
        return implementationVersion();
    }

    public EngineExecutionRequest validateFor(ExecutionEngineDescriptor descriptor) {
        ExecutionEngineDescriptor target = Objects.requireNonNull(
                descriptor, "Execution engine descriptor must not be null");
        if (!engineId().equals(target.engineId())
                || !implementationVersion().equals(target.implementationVersion())) {
            throw new IllegalArgumentException(
                    "Execution request does not target the selected engine");
        }
        return this;
    }

    @Override
    public String toString() {
        return "EngineExecutionRequest[executionId=" + executionId()
                + ", secretAccess=REDACTED]";
    }

    private static void requireConsistentPreparation(
            ExecutionContext context, SourcePreparationResult preparation) {
        if (preparation.workspace() == null
                || preparation.workspace().state() != WorkspaceState.READY
                || !context.executionId().equals(preparation.workspace().executionId())
                || preparation.source() == null) {
            throw invalidPreparation();
        }
        SourceMaterializationResult source = preparation.source();
        if (!preparation.workspace().workspaceId().equals(source.workspaceId())) {
            throw invalidPreparation();
        }
        WorkspaceMetadata metadata = preparation.workspace().metadata();
        ExecutionSourceReference sourceReference = metadata == null
                ? null
                : metadata.sourceReference();
        if (sourceReference == null
                || sourceReference.sourceType() != source.sourceType()
                || !sourceReference.revision().equals(source.resolvedRevision())) {
            throw invalidPreparation();
        }
    }

    private static IllegalArgumentException invalidPreparation() {
        return new IllegalArgumentException(
                "Prepared workspace and source identities are inconsistent");
    }
}
