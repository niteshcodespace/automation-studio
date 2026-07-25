package com.automationstudio.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.automationstudio.api.domain.EnvironmentType;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.security.SensitiveKeyDetector;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ExecutionSnapshotFactoryTest {

    private final ExecutionSnapshotFactory factory =
            new ExecutionSnapshotFactory(new SensitiveKeyDetector());

    @Test
    void environmentSnapshotRetainsReferencesAndRemovesResolvedSecretsRecursively() {
        Environment environment = new Environment();
        ReflectionTestUtils.setField(environment, "id", UUID.randomUUID());
        environment.setName("QA");
        environment.setType(EnvironmentType.TEST);
        environment.setBaseUrl("https://example.test");
        environment.setConfiguration(Map.of(
                "browser", "chromium",
                "password", "resolved",
                "nested", Map.of("api_token", "resolved", "region", "eu")));
        environment.setSecretReferences(Map.of("password", "vault://qa/password"));

        Map<String, Object> snapshot = factory.environment(environment);

        Map<?, ?> configuration = (Map<?, ?>) snapshot.get("configuration");
        assertThat(configuration.get("browser")).isEqualTo("chromium");
        assertThat(configuration.containsKey("password")).isFalse();
        Map<?, ?> nested = (Map<?, ?>) configuration.get("nested");
        assertThat(nested.get("region")).isEqualTo("eu");
        assertThat(nested.containsKey("api_token")).isFalse();
        assertThat(((Map<?, ?>) snapshot.get("secretReferences")).get("password"))
                .isEqualTo("vault://qa/password");
    }
}
