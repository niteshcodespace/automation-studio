package com.automationstudio.engine.karate;

import static org.junit.jupiter.api.Assertions.*;
import com.automationstudio.engine.conformance.InMemoryWorkspaceAccess;
import com.automationstudio.engine.sdk.PreparedSourceEntry;
import com.automationstudio.engine.sdk.PreparedSourceEntryKind;
import com.automationstudio.engine.sdk.WorkspaceId;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KarateFeatureDiscoveryTest {
    @Test void discoversFeatureFilesRecursivelyInDeterministicOrder() {
        var access = new InMemoryWorkspaceAccess(new UUID(1, 1), new WorkspaceId(new UUID(2, 2)),
                KarateTestFixtures.features("features/z.feature", "features/nested/a.feature", "features/readme.md"));
        try (var source = access.openPreparedSource()) {
            assertEquals(List.of("features/nested/a.feature", "features/z.feature"),
                    new KarateFeatureDiscovery().discover(source, KarateSuiteConfiguration.parse(KarateTestFixtures.configuration())));
        }
    }

    @Test void failsClosedForLinksUnsupportedKindsAndUnavailableListing() {
        assertEquals("SOURCE_LINK_DENIED", discoverFailure(PreparedSourceEntryKind.LINK).code());
        assertEquals("UNSUPPORTED_SOURCE_ENTRY", discoverFailure(PreparedSourceEntryKind.UNSUPPORTED).code());
        var unavailable = new Source(List.of()) { @Override public List<PreparedSourceEntry> list(String d, int m) { throw new UnsupportedOperationException(); } };
        assertEquals("SOURCE_LISTING_UNAVAILABLE", assertThrows(KarateEngineException.class,
                () -> new KarateFeatureDiscovery().discover(unavailable, KarateSuiteConfiguration.parse(KarateTestFixtures.configuration()))).code());
    }

    @Test void rejectsEmptySelectionAndOversizedFeature() {
        assertEquals("EMPTY_FEATURE_SELECTION", assertThrows(KarateEngineException.class,
                () -> new KarateFeatureDiscovery().discover(new Source(List.of()), KarateSuiteConfiguration.parse(KarateTestFixtures.configuration()))).code());
        var oversized = new Source(List.of(new PreparedSourceEntry("features/a.feature", PreparedSourceEntryKind.FILE, 1_048_577)));
        assertEquals("FEATURE_SIZE_LIMIT_EXCEEDED", assertThrows(KarateEngineException.class,
                () -> new KarateFeatureDiscovery().discover(oversized, KarateSuiteConfiguration.parse(KarateTestFixtures.configuration()))).code());
    }

    @Test void enforcesFeatureAggregateEntryAndDepthBounds() {
        var twoFeatures = new Source(List.of(
                new PreparedSourceEntry("features/a.feature", PreparedSourceEntryKind.FILE, 1),
                new PreparedSourceEntry("features/b.feature", PreparedSourceEntryKind.FILE, 1)));
        assertEquals("DISCOVERY_LIMIT_EXCEEDED", failureWith(twoFeatures,
                Map.of("maxFeatures", 1, "maxFeatureBytes", 1, "maxAggregateBytes", 1)).code());
        var tooMany = new Source(List.of(
                new PreparedSourceEntry("features/a.feature", PreparedSourceEntryKind.FILE, 1),
                new PreparedSourceEntry("features/b.feature", PreparedSourceEntryKind.FILE, 1)));
        assertEquals("DISCOVERY_LIMIT_EXCEEDED", failureWith(tooMany,
                Map.of("maxFeatures", 2, "maxEntriesPerDirectory", 1)).code());
        assertEquals("DISCOVERY_LIMIT_EXCEEDED", failureWith(tooMany,
                Map.of("maxFeatures", 2, "maxFeatureBytes", 1, "maxAggregateBytes", 1)).code());
        var directory = new Source(List.of()) {
            @Override public List<PreparedSourceEntry> list(String path, int max) {
                String child = path.equals("features") ? "features/nested" : "features/nested/deeper";
                return List.of(new PreparedSourceEntry(child, PreparedSourceEntryKind.DIRECTORY,
                        PreparedSourceEntry.UNKNOWN_SIZE));
            }
        };
        assertEquals("DISCOVERY_LIMIT_EXCEEDED", failureWith(directory, Map.of("maxDepth", 1)).code());
    }

    private KarateEngineException discoverFailure(PreparedSourceEntryKind kind) {
        var source = new Source(List.of(new PreparedSourceEntry("features/item", kind, PreparedSourceEntry.UNKNOWN_SIZE)));
        return assertThrows(KarateEngineException.class,
                () -> new KarateFeatureDiscovery().discover(source, KarateSuiteConfiguration.parse(KarateTestFixtures.configuration())));
    }

    private KarateEngineException failureWith(Source source, Map<String, Object> limits) {
        var configuration = new java.util.LinkedHashMap<String, Object>(KarateTestFixtures.configuration());
        configuration.put("limits", limits);
        return assertThrows(KarateEngineException.class,
                () -> new KarateFeatureDiscovery().discover(source, KarateSuiteConfiguration.parse(configuration)));
    }

    private static class Source implements com.automationstudio.engine.sdk.PreparedSourceAccess {
        private final List<PreparedSourceEntry> entries;
        Source(List<PreparedSourceEntry> entries) { this.entries = entries; }
        public WorkspaceId workspaceId() { return new WorkspaceId(new UUID(3, 3)); }
        public boolean isOpen() { return true; }
        public InputStream open(String path) { throw new AssertionError("content must not be opened"); }
        public List<PreparedSourceEntry> list(String directory, int max) { return entries; }
        public void close() { }
    }
}
