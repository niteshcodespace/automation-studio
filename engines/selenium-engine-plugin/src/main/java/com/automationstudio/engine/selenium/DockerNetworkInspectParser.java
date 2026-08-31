package com.automationstudio.engine.selenium;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strict parser for one Docker CLI response containing exactly one network object. */
final class DockerNetworkInspectParser {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    static DockerNetworkFingerprint parse(String raw, DockerNetworkSpec expected, String requestedId) {
        try {
            JsonNode array = JSON.readTree(raw);
            if (array == null || !array.isArray() || array.size() != 1) throw invalid();
            JsonNode root = array.get(0); if (!root.isObject()) throw invalid();
            String id = text(root, "Id"); if (!id.equals(requestedId)) throw invalid();
            exact(text(root, "Driver"), "bridge"); exact(text(root, "Scope"), "local");
            exact(bool(root, "Internal"), false); exact(bool(root, "Attachable"), false);
            exact(bool(root, "Ingress"), false); exact(bool(root, "ConfigOnly"), false);
            emptyConfigFrom(root.get("ConfigFrom"));
            exact(bool(root, "EnableIPv4"), true); exact(bool(root, "EnableIPv6"), false);
            JsonNode ipam = object(root, "IPAM"); exact(text(ipam, "Driver"), "default");
            requireOnly(ipam, "Driver", "Options", "Config");
            emptyObjectOrAbsent(ipam.get("Options"));
            JsonNode configs = ipam.get("Config");
            if (configs == null || !configs.isArray() || configs.size() != 1) throw invalid();
            JsonNode config = configs.get(0); if (!config.isObject()) throw invalid();
            requireOnly(config, "Subnet", "Gateway", "IPRange", "AuxiliaryAddresses");
            String subnet = text(config, "Subnet"), gateway = text(config, "Gateway");
            emptyTextOrAbsent(config.get("IPRange"));
            emptyObjectOrAbsent(config.get("AuxiliaryAddresses"));
            emptyObjectOrAbsent(root.get("Options"));
            Map<String, String> labels = labels(object(root, "Labels"));
            for (String key : labels.keySet())
                if (key.startsWith("com.automationstudio.") && !expected.labels().containsKey(key))
                    throw invalid();
            for (var entry : expected.labels().entrySet())
                if (!entry.getValue().equals(labels.get(entry.getKey()))) throw invalid();
            return new DockerNetworkFingerprint(id,
                    UUID.fromString(labels.get(DockerNetworkSpec.EXECUTION)),
                    Long.parseUnsignedLong(labels.get(DockerNetworkSpec.REVISION)),
                    labels.get(DockerNetworkSpec.ATTEMPT), labels.get(DockerNetworkSpec.DEADLINE),
                    subnet, gateway, expected.labels());
        } catch (RuntimeException failure) {
            if (failure.getMessage() != null
                    && failure.getMessage().equals("Malformed authoritative Docker network inspection"))
                throw failure;
            throw invalid();
        }
    }

    private static Map<String, String> labels(JsonNode value) {
        var result = new LinkedHashMap<String, String>();
        value.properties().forEach(entry -> {
            if (!entry.getValue().isTextual()) throw invalid();
            result.put(entry.getKey(), entry.getValue().asText());
        }); return Map.copyOf(result);
    }
    private static JsonNode object(JsonNode owner, String field) {
        JsonNode value = owner == null ? null : owner.get(field);
        if (value == null || !value.isObject()) throw invalid(); return value;
    }
    private static String text(JsonNode owner, String field) {
        JsonNode value = owner == null ? null : owner.get(field);
        if (value == null || !value.isTextual()) throw invalid(); return value.asText();
    }
    private static boolean bool(JsonNode owner, String field) {
        JsonNode value = owner == null ? null : owner.get(field);
        if (value == null || !value.isBoolean()) throw invalid(); return value.asBoolean();
    }
    private static void emptyObjectOrAbsent(JsonNode value) {
        if (value == null || value.isNull()) return;
        if (!value.isObject() || value.size() != 0) throw invalid();
    }
    private static void emptyConfigFrom(JsonNode value) {
        if (value == null || value.isNull()) return;
        if (!value.isObject()) throw invalid();
        if (value.size() == 0) return;
        requireOnly(value, "Network");
        if (value.size() != 1 || !text(value, "Network").isEmpty()) throw invalid();
    }
    private static void emptyTextOrAbsent(JsonNode value) {
        if (value == null || value.isNull()) return;
        if (!value.isTextual() || !value.asText().isEmpty()) throw invalid();
    }
    private static void exact(String actual, String expected) { if (!expected.equals(actual)) throw invalid(); }
    private static void exact(boolean actual, boolean expected) { if (actual != expected) throw invalid(); }
    private static void requireOnly(JsonNode object, String... allowed) {
        java.util.Set<String> names = java.util.Set.of(allowed);
        object.properties().forEach(entry -> { if (!names.contains(entry.getKey())) throw invalid(); });
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Malformed authoritative Docker network inspection");
    }
    private DockerNetworkInspectParser() {}
}
