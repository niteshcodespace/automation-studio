package com.automationstudio.api.execution.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.secret.provider.environment.OperatorEnvironmentSecretProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SecretResolutionConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SecretResolutionConfiguration.class);

    @Test
    void operatorEnvironmentProviderIsDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OperatorEnvironmentSecretProvider.class);
            assertThat(context).hasSingleBean(ExecutionSecretProviderRegistry.class);
            assertThat(context).hasSingleBean(ExecutionSecretScopeFactory.class);
            assertThatThrownBy(() -> context.getBean(ExecutionSecretProviderRegistry.class)
                            .resolve(OperatorEnvironmentSecretProvider.PROVIDER_ID))
                    .isInstanceOf(SecretResolutionException.class)
                    .satisfies(failure -> assertThat(((SecretResolutionException) failure).code())
                            .isEqualTo("SECRET_PROVIDER_UNAVAILABLE"));
        });
    }

    @Test
    void explicitPropertyEnablesExactlyOneOperatorEnvironmentProvider() {
        contextRunner
                .withPropertyValues(
                        "automation.runner.secrets.operator-environment.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OperatorEnvironmentSecretProvider.class);
                    assertThat(context.getBean(ExecutionSecretProviderRegistry.class)
                                    .resolve(OperatorEnvironmentSecretProvider.PROVIDER_ID))
                            .isSameAs(context.getBean(OperatorEnvironmentSecretProvider.class));
                });
    }

}
