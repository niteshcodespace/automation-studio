package com.automationstudio.api.execution.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.engine.playwright.PlaywrightExecutionException;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightManifestException;
import com.automationstudio.api.execution.orchestration.ExecutionOrchestrationException;
import com.automationstudio.api.execution.orchestration.ExecutionOrchestrationResult;
import org.junit.jupiter.api.Test;

class OrangeHrmQualificationDiagnosticProbeTest {

    private static final String SECRET_CANARY = "credential-bearing-secret-canary";

    @Test
    void mapsKnownFailuresToFixedSanitizedClassifications() {
        var manifest = new PlaywrightManifestException("MANIFEST_MISSING", SECRET_CANARY);
        var engine = new PlaywrightExecutionException(
                "PLAYWRIGHT_MANIFEST_LOAD_FAILED", "fixed public message", manifest);
        var failure = new ExecutionOrchestrationException(
                "ENGINE_EXECUTION_FAILED", "Execution engine failed", engine);
        var probe = failingProbe(failure);

        assertThatThrownBy(() -> probe.execute(null)).isSameAs(failure);

        assertThat(probe.diagnostic()).contains(new OrangeHrmQualificationDiagnosticProbe.Diagnostic(
                OrangeHrmQualificationDiagnosticProbe.Stage.MANIFEST_LOADING,
                "PlaywrightManifestException", "MANIFEST_MISSING"));
        assertThat(probe.diagnostic().orElseThrow().toString())
                .doesNotContain(SECRET_CANARY, "username", "password", "http", "selector");
    }

    @Test
    void mapsUnknownFailureWithoutCapturingItsMessage() {
        RuntimeException failure = new IllegalStateException(SECRET_CANARY);
        var probe = failingProbe(failure);

        assertThatThrownBy(() -> probe.execute(null)).isSameAs(failure);

        assertThat(probe.diagnostic()).contains(new OrangeHrmQualificationDiagnosticProbe.Diagnostic(
                OrangeHrmQualificationDiagnosticProbe.Stage.UNKNOWN_POST_START,
                "IllegalStateException", null));
        assertThat(probe.diagnostic().orElseThrow().toString()).doesNotContain(SECRET_CANARY);
    }

    @Test
    void mapsInvalidEngineRequestToFixedSanitizedValidationStage() {
        var engine = new PlaywrightExecutionException(
                "INVALID_PLAYWRIGHT_EXECUTION_REQUEST", SECRET_CANARY);
        var failure = new ExecutionOrchestrationException(
                "ENGINE_EXECUTION_FAILED", "Execution engine failed", engine);
        var probe = failingProbe(failure);

        assertThatThrownBy(() -> probe.execute(null)).isSameAs(failure);

        assertThat(probe.diagnostic()).contains(new OrangeHrmQualificationDiagnosticProbe.Diagnostic(
                OrangeHrmQualificationDiagnosticProbe.Stage.ENGINE_REQUEST_VALIDATION,
                "PlaywrightExecutionException", "INVALID_PLAYWRIGHT_EXECUTION_REQUEST"));
        assertThat(probe.diagnostic().orElseThrow().toString()).doesNotContain(SECRET_CANARY);
    }

    @Test
    void rejectsUnapprovedTypedErrorCodeFromDiagnosticOutput() {
        RuntimeException failure = new PlaywrightManifestException(SECRET_CANARY, SECRET_CANARY);
        var probe = failingProbe(failure);

        assertThatThrownBy(() -> probe.execute(null)).isSameAs(failure);

        assertThat(probe.diagnostic()).contains(new OrangeHrmQualificationDiagnosticProbe.Diagnostic(
                OrangeHrmQualificationDiagnosticProbe.Stage.MANIFEST_LOADING,
                "PlaywrightManifestException", null));
        assertThat(probe.diagnostic().orElseThrow().toString()).doesNotContain(SECRET_CANARY);
    }

    @Test
    void producesNoDiagnosticWhenDelegateSucceeds() {
        ExecutionOrchestrationResult result = org.mockito.Mockito.mock(
                ExecutionOrchestrationResult.class);
        var probe = new OrangeHrmQualificationDiagnosticProbe(request -> result);

        assertThat(probe.execute(null)).isSameAs(result);
        assertThat(probe.diagnostic()).isEmpty();
    }

    private static OrangeHrmQualificationDiagnosticProbe failingProbe(RuntimeException failure) {
        return new OrangeHrmQualificationDiagnosticProbe(request -> {
            throw failure;
        });
    }
}
