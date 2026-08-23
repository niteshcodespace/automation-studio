package com.automationstudio.engine.selenium.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.automationstudio.engine.selenium.SeleniumTestFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeleniumManifestParserTest {
    private final SeleniumManifestParser parser = new SeleniumManifestParser();

    @Test void parsesAllActionsInOrderIntoImmutableIdentityModel() {
        SeleniumManifest manifest = parser.parse(bytes(SeleniumTestFixtures.validManifest()));
        assertEquals("1.0", manifest.schemaVersion());
        assertIterableEquals(List.of(SeleniumActionType.values()), manifest.scenarios().getFirst()
                .steps().stream().map(SeleniumStep::action).toList());
        assertThrows(UnsupportedOperationException.class, () -> manifest.scenarios().clear());
        assertThrows(UnsupportedOperationException.class, () -> manifest.scenarios().getFirst().steps().clear());
    }

    @Test void rejectsUnknownFieldsActionsAndDuplicateIds() {
        rejects(SeleniumTestFixtures.validManifest().replace(
                "\"name\":\"Passive suite\"", "\"name\":\"Passive suite\",\"script\":\"run\""));
        rejects(SeleniumTestFixtures.validManifest().replace("\"navigation\"", "\"shell\""));
        rejects(SeleniumTestFixtures.validManifest().replace("\"step-2\"", "\"step-1\""));
        String duplicateScenario = "{\"schemaVersion\":\"1.0\",\"name\":\"x\",\"scenarios\":["
                + scenario("same", "one") + "," + scenario("same", "two") + "]}";
        rejects(duplicateScenario);
    }

    @Test void rejectsMalformedWrongSchemaAndForbiddenFieldCombinationsWithoutEchoingInput() {
        rejects("{not-json");
        rejects(SeleniumTestFixtures.validManifest().replace("\"1.0\"", "\"2.0\""));
        String sensitive = SeleniumTestFixtures.validManifest().replace(
                "\"selector\":\"#login\"", "\"selector\":\"PRIVATE_SELECTOR\",\"value\":\"PRIVATE_VALUE\"");
        String message = assertThrows(
                SeleniumManifestException.class, () -> parser.parse(bytes(sensitive))).getMessage();
        assertFalse(message.contains("PRIVATE_SELECTOR"));
        assertFalse(message.contains("PRIVATE_VALUE"));
    }

    @Test void enforcesByteDepthStringScenarioAndStepBounds() {
        assertEquals("MANIFEST_TOO_LARGE", assertThrows(SeleniumManifestException.class,
                () -> parser.parse(new byte[SeleniumManifestParser.MAX_MANIFEST_BYTES + 1])).code());
        String deep = "{\"schemaVersion\":\"1.0\",\"name\":\"x\",\"scenarios\":"
                + "[".repeat(34) + "]".repeat(34) + "}";
        rejects(deep);
        rejects(SeleniumTestFixtures.validManifest().replace(
                "Passive suite", "x".repeat(SeleniumManifest.MAX_NAME_LENGTH + 1)));
        List<String> scenarios = new ArrayList<>();
        for (int index = 0; index <= SeleniumManifest.MAX_SCENARIOS; index++) {
            scenarios.add(scenario("s" + index, "step" + index));
        }
        rejects("{\"schemaVersion\":\"1.0\",\"name\":\"x\",\"scenarios\":["
                + String.join(",", scenarios) + "]}");
        String steps = "{\"id\":\"s\",\"name\":\"s\",\"steps\":["
                + String.join(",", java.util.Collections.nCopies(
                        SeleniumScenario.MAX_STEPS + 1,
                        "{\"id\":\"x\",\"action\":\"click\",\"selector\":\"#x\"}")) + "]}";
        rejects("{\"schemaVersion\":\"1.0\",\"name\":\"x\",\"scenarios\":[" + steps + "]}");
    }

    @Test void rejectsUnsafeReferences() {
        var fixture = SeleniumTestFixtures.request(50);
        try (var source = fixture.workspace().openPreparedSource()) {
            for (String reference : List.of("../selenium.json", "/selenium.json", "C:selenium.json", "a\\b")) {
                assertEquals("Selenium manifest location is invalid", assertThrows(
                        SeleniumManifestException.class, () -> parser.load(reference, source)).getMessage());
            }
        }
    }

    @Test void acceptsGlobalStepLimitAndRejectsStepFiveThousandOneBeforeParsingIt() {
        SeleniumManifest accepted = parser.parse(bytes(manifestWithSteps(
                SeleniumManifest.MAX_TOTAL_STEPS, false)));
        assertEquals(SeleniumManifest.MAX_TOTAL_STEPS, accepted.scenarios().stream()
                .mapToInt(scenario -> scenario.steps().size()).sum());

        SeleniumManifestException failure = assertThrows(SeleniumManifestException.class,
                () -> parser.parse(bytes(manifestWithSteps(
                        SeleniumManifest.MAX_TOTAL_STEPS + 1, true))));
        assertEquals("INVALID_MANIFEST", failure.code());
        assertEquals("Selenium manifest is invalid", failure.getMessage());
        for (String sensitive : List.of("PRIVATE_SELECTOR", "PRIVATE_VALUE", "PRIVATE_URL",
                "C:/private/source.json", "PRIVATE_SOURCE_CONTENT", "PRIVATE_SECRET")) {
            assertFalse(failure.getMessage().contains(sensitive));
        }
    }

    private void rejects(String json) {
        assertThrows(SeleniumManifestException.class, () -> parser.parse(bytes(json)));
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String scenario(String id, String step) {
        return "{\"id\":\"" + id + "\",\"name\":\"x\",\"steps\":[{\"id\":\""
                + step + "\",\"action\":\"click\",\"selector\":\"#x\"}]}";
    }

    private static String manifestWithSteps(int totalSteps, boolean poisonLastStep) {
        List<String> scenarios = new ArrayList<>();
        int remaining = totalSteps;
        int nextStep = 0;
        while (remaining > 0) {
            int scenarioSteps = Math.min(remaining, SeleniumScenario.MAX_STEPS);
            List<String> steps = new ArrayList<>(scenarioSteps);
            for (int index = 0; index < scenarioSteps; index++) {
                boolean poison = poisonLastStep && nextStep == SeleniumManifest.MAX_TOTAL_STEPS;
                steps.add(poison
                        ? "{\"id\":\"step-" + nextStep + "\",\"action\":\"sensitive-fill\","
                                + "\"selector\":\"PRIVATE_SELECTOR\",\"value\":\"PRIVATE_VALUE\","
                                + "\"url\":\"PRIVATE_URL\",\"secretRef\":\"PRIVATE_SECRET\","
                                + "\"localPath\":\"C:/private/source.json\","
                                + "\"source\":\"PRIVATE_SOURCE_CONTENT\"}"
                        : "{\"id\":\"step-" + nextStep + "\",\"action\":\"click\","
                                + "\"selector\":\"#x\"}");
                nextStep++;
            }
            scenarios.add("{\"id\":\"scenario-" + scenarios.size()
                    + "\",\"name\":\"x\",\"steps\":[" + String.join(",", steps) + "]}");
            remaining -= scenarioSteps;
        }
        return "{\"schemaVersion\":\"1.0\",\"name\":\"x\",\"scenarios\":["
                + String.join(",", scenarios) + "]}";
    }
}
