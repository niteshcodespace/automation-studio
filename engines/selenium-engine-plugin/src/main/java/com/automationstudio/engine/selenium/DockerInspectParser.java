package com.automationstudio.engine.selenium;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

/** Strict single-response parser for the locked Contract 5 fingerprint. */
final class DockerInspectParser {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    static DockerResourceFingerprint parse(String raw) {
        try {
            JsonNode array = JSON.readTree(raw);
            if (array == null || !array.isArray() || array.size() != 1) throw invalid();
            JsonNode root = array.get(0), config = object(root, "Config"), host = object(root, "HostConfig");
            JsonNode labels = object(config, "Labels"), restart = object(host, "RestartPolicy");
            ContainmentResourceRole role = ContainmentResourceRole.valueOf(text(labels, "automation-studio.role"));
            return new DockerResourceFingerprint(text(root, "Id"),
                    UUID.fromString(text(labels, "automation-studio.execution")),
                    role,
                    text(labels, "automation-studio.attempt"), text(root, "Image"),
                    strings(config.get("Entrypoint")), strings(config.get("Cmd")), text(config, "User"),
                    bool(host, "ReadonlyRootfs"), text(restart, "Name"), integer(restart, "MaximumRetryCount"),
                    text(host, "NetworkMode"), bool(host, "Privileged"), text(host, "PidMode"),
                    text(host, "IpcMode"), isolation(host), authoritativeEnvironment(config.get("Env"), role));
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalArgumentException) throw failure;
            throw invalid();
        }
    }

    private static List<String> isolation(JsonNode host) {
        var result = new ArrayList<String>();
        add(result, "cap-add", host.get("CapAdd")); add(result, "cap-drop", host.get("CapDrop"));
        devices(result, host.get("Devices")); add(result, "bind", host.get("Binds"));
        mounts(result, host.get("Mounts")); add(result, "security-opt", host.get("SecurityOpt"));
        tmpfs(result, host.get("Tmpfs"));
        return result;
    }
    private static void add(List<String> target, String prefix, JsonNode values) {
        for (String value : strings(values)) target.add(prefix + "=" + value);
    }
    private static void devices(List<String> target, JsonNode values) {
        for (JsonNode value : objects(values)) target.add("device=" + text(value, "PathOnHost") + "|"
                + text(value, "PathInContainer") + "|" + text(value, "CgroupPermissions"));
    }
    private static void mounts(List<String> target, JsonNode values) {
        for (JsonNode value : objects(values)) target.add("mount=" + text(value, "Type") + "|"
                + text(value, "Source") + "|" + text(value, "Target") + "|"
                + bool(value, "ReadOnly") + "|" + text(value, "Consistency"));
    }
    private static void tmpfs(List<String> target, JsonNode value) {
        if (value == null || value.isNull() || !value.isObject()) throw invalid();
        value.properties().forEach(entry -> {
            if (!entry.getValue().isTextual()) throw invalid();
            target.add("tmpfs=" + entry.getKey() + "=" + entry.getValue().asText());
        });
    }
    private static List<JsonNode> objects(JsonNode value) {
        if (value == null || value.isNull() || !value.isArray()) throw invalid();
        var result = new ArrayList<JsonNode>();
        for (JsonNode element : value) { if (!element.isObject()) throw invalid(); result.add(element); }
        return result;
    }
    private static List<String> authoritativeEnvironment(JsonNode value, ContainmentResourceRole role) {
        List<String> all = strings(value); var result = new ArrayList<String>();
        String key = role == ContainmentResourceRole.GATEWAY ? "AS_GATEWAY_MODE=" : "LANG=";
        for (String entry : all) if (entry.startsWith(key)) result.add(entry);
        if (result.size() != 1) throw invalid();
        return result;
    }
    private static JsonNode object(JsonNode owner, String field) {
        JsonNode value = owner == null ? null : owner.get(field);
        if (value == null || !value.isObject()) throw invalid();
        return value;
    }
    private static String text(JsonNode owner, String field) {
        JsonNode value = owner == null ? null : owner.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        return value.asText();
    }
    private static boolean bool(JsonNode owner, String field) {
        JsonNode value = owner == null ? null : owner.get(field);
        if (value == null || !value.isBoolean()) throw invalid();
        return value.asBoolean();
    }
    private static long integer(JsonNode owner, String field) {
        JsonNode value = owner == null ? null : owner.get(field);
        if (value == null || !value.isIntegralNumber()) throw invalid();
        return value.asLong();
    }
    private static List<String> strings(JsonNode value) {
        if (value == null || value.isNull()) throw invalid();
        if (!value.isArray()) throw invalid();
        var result = new ArrayList<String>();
        for (JsonNode element : value) {
            if (!element.isTextual()) throw invalid();
            result.add(element.asText());
        }
        return result;
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Malformed authoritative Docker inspection");
    }
    private DockerInspectParser() {}
}
