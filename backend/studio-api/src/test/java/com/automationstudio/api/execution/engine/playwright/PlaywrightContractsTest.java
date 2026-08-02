package com.automationstudio.api.execution.engine.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlaywrightContractsTest {

    @Test
    void exposesStableDescriptorWithoutRegisteringAnEngine() {
        var descriptor = PlaywrightEngineDescriptor.descriptor();

        assertThat(descriptor.engineName()).isEqualTo("playwright-java");
        assertThat(descriptor.engineVersion()).isEqualTo("1.61.0");
        assertThat(descriptor.supportedCapabilities()).containsExactly("chromium");
        assertThat(descriptor.supportedFeatures()).containsExactly("declarative-scenario");
        assertThat(PlaywrightEngineDescriptor.descriptor()).isSameAs(descriptor);
    }
}
