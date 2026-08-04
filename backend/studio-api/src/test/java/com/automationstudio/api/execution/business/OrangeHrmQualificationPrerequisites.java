package com.automationstudio.api.execution.business;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.junit.jupiter.api.Assumptions;

public final class OrangeHrmQualificationPrerequisites {

    static final String ENABLED = "automation.as025g.enabled";
    static final String TARGET_URL = "automation.as025g.target-url";
    static final String TARGET_CLASSIFICATION = "automation.as025g.target-classification";
    static final String BROWSER_EXECUTABLE = "automation.runner.playwright.executable-path";
    static final String PROVIDER_ENABLED =
            "automation.runner.secrets.operator-environment.enabled";
    static final String BROWSER_PRODUCT = "automation.as025g.browser-product";
    static final String BROWSER_BUILD = "automation.as025g.browser-build";
    static final String USERNAME_KEY = "AUTOMATION_SECRET_ORANGEHRM_USERNAME";
    static final String PASSWORD_KEY = "AUTOMATION_SECRET_ORANGEHRM_PASSWORD";
    static final String APPROVED_DEMO_ORIGIN = "https://opensource-demo.orangehrmlive.com";

    private OrangeHrmQualificationPrerequisites() { }

    public static Configuration configuredOrSkip() {
        Decision decision = evaluate(
                systemProperties(), System.getenv()::containsKey,
                path -> Files.isRegularFile(path) && Files.isExecutable(path));
        Assumptions.assumeTrue(decision.ready(), decision.reason());
        return decision.configuration();
    }

    static Decision evaluate(
            Map<String, String> properties,
            Predicate<String> credentialPresent,
            Predicate<Path> executableCheck) {
        Objects.requireNonNull(properties);
        Objects.requireNonNull(credentialPresent);
        Objects.requireNonNull(executableCheck);
        if (!"true".equals(properties.get(ENABLED))) {
            return Decision.skip("AS-025G qualification is not explicitly enabled");
        }
        String executableValue = properties.get(BROWSER_EXECUTABLE);
        Path executable;
        try {
            executable = executableValue == null ? null : Path.of(executableValue);
        } catch (InvalidPathException failure) {
            executable = null;
        }
        if (executable == null || !executable.isAbsolute() || !executableCheck.test(executable)) {
            return Decision.skip("AS-025G Chromium executable is unavailable");
        }
        if (!"true".equals(properties.get(PROVIDER_ENABLED))) {
            return Decision.skip("AS-025G secret provider is unavailable");
        }
        String target = properties.get(TARGET_URL);
        if (!approvedTarget(target, properties.get(TARGET_CLASSIFICATION))) {
            return Decision.skip("AS-025G approved non-production target is unavailable");
        }
        String browserProduct = properties.get(BROWSER_PRODUCT);
        String browserBuild = properties.get(BROWSER_BUILD);
        if (missing(browserProduct) || missing(browserBuild)) {
            return Decision.skip("AS-025G browser qualification metadata is unavailable");
        }
        if (!credentialPresent.test(USERNAME_KEY) || !credentialPresent.test(PASSWORD_KEY)) {
            return Decision.skip("AS-025G operator credentials are unavailable");
        }
        return Decision.ready(new Configuration(
                executable, URI.create(target), "NON_PRODUCTION", browserProduct, browserBuild,
                USERNAME_KEY, PASSWORD_KEY));
    }

    private static boolean approvedTarget(String target, String classification) {
        if (!"NON_PRODUCTION".equals(classification) || target == null) {
            return false;
        }
        try {
            URI uri = URI.create(target);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && !host.endsWith(".")
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && (uri.getPath() == null || uri.getPath().isEmpty())
                    && target.equals(APPROVED_DEMO_ORIGIN);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }

    private static Map<String, String> systemProperties() {
        return Map.of(
                ENABLED, System.getProperty(ENABLED, ""),
                TARGET_URL, System.getProperty(TARGET_URL, ""),
                TARGET_CLASSIFICATION, System.getProperty(TARGET_CLASSIFICATION, ""),
                BROWSER_EXECUTABLE, System.getProperty(BROWSER_EXECUTABLE, ""),
                PROVIDER_ENABLED, System.getProperty(PROVIDER_ENABLED, ""),
                BROWSER_PRODUCT, System.getProperty(BROWSER_PRODUCT, ""),
                BROWSER_BUILD, System.getProperty(BROWSER_BUILD, ""));
    }

    public record Configuration(
            Path browserExecutable,
            URI target,
            String targetClassification,
            String browserProduct,
            String browserBuild,
            String usernameKey,
            String passwordKey) {
        @Override
        public String toString() {
            return "OrangeHrmQualificationConfiguration[REDACTED]";
        }
    }

    record Decision(boolean ready, String reason, Configuration configuration) {
        static Decision skip(String reason) {
            return new Decision(false, reason, null);
        }

        static Decision ready(Configuration configuration) {
            return new Decision(true, "AS-025G qualification prerequisites are satisfied", configuration);
        }
    }
}
