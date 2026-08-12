package com.automationstudio.api.execution.engine.restassured;

import com.automationstudio.engine.restassured.RestAssuredEnginePlugin;
import com.automationstudio.engine.restassured.manifest.RestAssuredManifestParser;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Static production assembly into the existing Spring-collected engine registry. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "automation.runner.workspace.root")
public class RestAssuredExecutionEngineConfiguration {

    @Bean
    RestAssuredEnginePlugin restAssuredEnginePlugin(Clock clock) {
        return new RestAssuredEnginePlugin(new RestAssuredManifestParser(), clock);
    }
}
