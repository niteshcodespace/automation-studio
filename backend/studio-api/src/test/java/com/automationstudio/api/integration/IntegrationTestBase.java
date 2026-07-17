package com.automationstudio.api.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
public abstract class IntegrationTestBase {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final String DATABASE_NAME = "automation_studio_test";
    private static final String DATABASE_USERNAME = "test";
    private static final String DATABASE_PASSWORD = "test";

    protected static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER;

    static {
        POSTGRESQL_CONTAINER =
                new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName(DATABASE_NAME)
                    .withUsername(DATABASE_USERNAME)
                    .withPassword(DATABASE_PASSWORD);

        POSTGRESQL_CONTAINER.start();
    }

    @DynamicPropertySource
    static void registerPostgreSqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL_CONTAINER::getPassword);
        registry.add("spring.flyway.url", POSTGRESQL_CONTAINER::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRESQL_CONTAINER::getUsername);
        registry.add("spring.flyway.password", POSTGRESQL_CONTAINER::getPassword);
    }
}
