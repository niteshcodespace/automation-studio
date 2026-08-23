package com.automationstudio.engine.selenium;

import com.automationstudio.engine.selenium.manifest.SeleniumManifestParser;
import com.automationstudio.engine.sdk.EngineExecutionContext;
import com.automationstudio.engine.sdk.EngineExecutionRequest;
import com.automationstudio.engine.sdk.EngineExecutionResult;
import com.automationstudio.engine.sdk.ExecutionEngineDescriptor;
import com.automationstudio.engine.sdk.ExecutionEnginePlugin;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Passive AS-031C provider foundation. Runtime execution is intentionally unavailable. */
public final class SeleniumEnginePlugin implements ExecutionEnginePlugin {
    public static final String ENGINE_ID = "selenium-java";
    public static final String IMPLEMENTATION_VERSION = "1.0.0";
    private static final ExecutionEngineDescriptor DESCRIPTOR = new ExecutionEngineDescriptor(
            ENGINE_ID, IMPLEMENTATION_VERSION, "Selenium Java Engine",
            Set.of("prepared-source", "selenium-manifest"),
            Set.of("strict-configuration", "bounded-manifest-validation", "browser-inert"));

    private final SeleniumManifestParser parser;
    private final SeleniumExecutionRuntime runtime;

    public SeleniumEnginePlugin() {
        this(new SeleniumManifestParser(), (manifest, configuration) -> {
            throw new SeleniumEngineException(
                    "SELENIUM_RUNTIME_NOT_AVAILABLE", "Selenium runtime is not available");
        });
    }

    SeleniumEnginePlugin(SeleniumManifestParser parser, SeleniumExecutionRuntime runtime) {
        this.parser = Objects.requireNonNull(parser, "Manifest parser must not be null");
        this.runtime = Objects.requireNonNull(runtime, "Execution runtime must not be null");
    }

    @Override public ExecutionEngineDescriptor descriptor() { return DESCRIPTOR; }

    @Override
    public void validate(EngineExecutionContext context) {
        if (context == null || !DESCRIPTOR.identity().equals(context.engineIdentity())
                || !context.environmentConfiguration().equals(Map.of())) {
            throw new SeleniumEngineException("INVALID_ENGINE_CONTEXT", "Selenium engine context is invalid");
        }
        SeleniumSuiteConfiguration.parse(context.suiteConfiguration());
        validateReference(context.suiteReference());
    }

    @Override
    public EngineExecutionResult execute(EngineExecutionRequest request) {
        EngineExecutionRequest validated;
        try {
            validated = Objects.requireNonNull(request, "request").validateFor(DESCRIPTOR);
            validate(validated.context());
        } catch (SeleniumEngineException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SeleniumEngineException("INVALID_ENGINE_REQUEST", "Selenium engine request is invalid");
        }
        try (var source = validated.workspaceAccess().openPreparedSource()) {
            var manifest = parser.load(validated.context().suiteReference(), source);
            runtime.execute(manifest, SeleniumSuiteConfiguration.parse(
                    validated.context().suiteConfiguration()));
        }
        throw new SeleniumEngineException(
                "SELENIUM_RUNTIME_NOT_AVAILABLE", "Selenium runtime is not available");
    }

    private static void validateReference(String reference) {
        if (reference == null || reference.isBlank()
                || reference.length() > SeleniumManifestParser.MAX_REFERENCE_LENGTH
                || reference.startsWith("/") || reference.contains("\\") || reference.contains(":")
                || java.util.List.of(reference.split("/", -1)).stream()
                        .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) {
            throw new SeleniumEngineException(
                    "INVALID_MANIFEST_REFERENCE", "Selenium manifest reference is invalid");
        }
    }
}
