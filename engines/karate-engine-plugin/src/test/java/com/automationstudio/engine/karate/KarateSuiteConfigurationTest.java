package com.automationstudio.engine.karate;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KarateSuiteConfigurationTest {
    @Test void acceptsBoundedProviderLocalConfiguration() {
        var sourceTags = new java.util.ArrayList<>(List.of("@smoke"));
        var source = new java.util.LinkedHashMap<String, Object>(KarateTestFixtures.configuration());
        source.put("includeTags", sourceTags);
        var parsed = KarateSuiteConfiguration.parse(source);
        sourceTags.add("@later");
        assertEquals("features", parsed.featureRoot());
        assertEquals(List.of("@smoke"), parsed.includeTags());
        assertEquals("logical-api-key", parsed.secretReferences().get("apiKey"));
        assertEquals(KarateSuiteConfiguration.Authentication.Type.NONE,parsed.authentication().type());
    }

    @Test void rejectsUnknownFieldsAndTraversal() {
        assertEquals("INVALID_CONFIGURATION", failure(Map.of("schemaVersion", "1", "featureRoot", "features", "extra", true)).code());
        assertEquals("INVALID_FEATURE_ROOT", failure(Map.of("schemaVersion", "1", "featureRoot", "../features")).code());
    }

    @Test void rejectsExpressionsDuplicatesAmbiguityAndReservedBindings() {
        assertEquals("INVALID_TAGS", failure(Map.of("schemaVersion", "1", "featureRoot", "features", "includeTags", List.of("@a or @b"))).code());
        assertEquals("INVALID_TAGS", failure(Map.of("schemaVersion", "1", "featureRoot", "features", "includeTags", List.of("@a", "@a"))).code());
        assertEquals("AMBIGUOUS_TAG_SELECTION", failure(Map.of("schemaVersion", "1", "featureRoot", "features", "includeTags", List.of("@a"), "excludeTags", List.of("@a"))).code());
        assertEquals("INVALID_BINDINGS", failure(Map.of("schemaVersion", "1", "featureRoot", "features", "variables", Map.of("system.path", "x"))).code());
    }

    @Test void rejectsExcessiveLimitsAndSecretNames() {
        assertEquals("INVALID_LIMITS", failure(Map.of("schemaVersion", "1", "featureRoot", "features", "limits", Map.of("maxDepth", 33))).code());
        assertEquals("SECRET_REFERENCE_INVALID", failure(Map.of("schemaVersion", "1", "featureRoot", "features", "secretReferences", Map.of("bad name", "logical"))).code());
    }

    @Test void validatesLogicalAuthenticationDeclarations(){var source=new java.util.LinkedHashMap<String,Object>(KarateTestFixtures.configuration());source.put("authentication",Map.of("type","bearer","secretRef","apiKey"));assertEquals(KarateSuiteConfiguration.Authentication.Type.BEARER,KarateSuiteConfiguration.parse(source).authentication().type());source.put("authentication",Map.of("type","api_key_header","secretRef","missing","placement","X-Key"));assertEquals("AUTH_CONFIGURATION_INVALID",failure(source).code());source.put("authentication",Map.of("type","api_key_header","secretRef","apiKey","placement","Host"));assertEquals("AUTH_CONFIGURATION_INVALID",failure(source).code());}

    private KarateEngineException failure(Map<String, Object> configuration) {
        return assertThrows(KarateEngineException.class, () -> KarateSuiteConfiguration.parse(configuration));
    }
}
