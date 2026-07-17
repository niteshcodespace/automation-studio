package com.automationstudio.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class ApplicationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertThat(POSTGRESQL_CONTAINER.isRunning()).isTrue();
        assertThat(applicationContext).isNotNull();
    }
}
