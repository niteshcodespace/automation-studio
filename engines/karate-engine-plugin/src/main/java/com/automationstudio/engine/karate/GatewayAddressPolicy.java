package com.automationstudio.engine.karate;

/** Immutable provider-owned gateway destination policy. */
public enum GatewayAddressPolicy {
    GLOBAL_ONLY("production"),
    LOOPBACK_ONLY_TEST("test-loopback");

    private final String commandValue;

    GatewayAddressPolicy(String commandValue) {
        this.commandValue = commandValue;
    }

    String commandValue() {
        return commandValue;
    }
}
