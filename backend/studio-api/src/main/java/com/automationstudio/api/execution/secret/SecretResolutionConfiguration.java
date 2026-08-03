package com.automationstudio.api.execution.secret;

import com.automationstudio.api.execution.secret.provider.environment.OperatorEnvironmentSecretProvider;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SecretResolutionConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "automation.runner.secrets.operator-environment.enabled",
            havingValue = "true")
    OperatorEnvironmentSecretProvider operatorEnvironmentSecretProvider() {
        return new OperatorEnvironmentSecretProvider(true, System::getenv);
    }

    @Bean
    ExecutionSecretProviderRegistry executionSecretProviderRegistry(
            List<ExecutionSecretProvider> providers) {
        return new ExecutionSecretProviderRegistry(providers);
    }

    @Bean
    ExecutionSecretScopeFactory executionSecretScopeFactory(
            ExecutionSecretProviderRegistry providers) {
        return new ExecutionSecretScopeFactory(providers);
    }
}
