package com.automationstudio.api.execution.engine.karate;

import com.automationstudio.engine.karate.KarateEnginePlugin;
import com.automationstudio.engine.karate.GatewayAddressPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Static production assembly into the authoritative Spring-collected registry. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="automation.runner.workspace.root")
public class KarateExecutionEngineConfiguration {
    @Bean KarateEnginePlugin karateEnginePlugin(){return new KarateEnginePlugin(GatewayAddressPolicy.GLOBAL_ONLY);}
}
