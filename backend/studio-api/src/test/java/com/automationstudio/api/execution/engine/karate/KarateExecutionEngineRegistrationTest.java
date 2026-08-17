package com.automationstudio.api.execution.engine.karate;

import static org.assertj.core.api.Assertions.*;
import com.automationstudio.api.execution.engine.*;
import com.automationstudio.engine.karate.KarateEnginePlugin;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KarateExecutionEngineRegistrationTest {
    @Test void staticallyRegistersExactIdentityOnce(){
        new ApplicationContextRunner().withSystemProperties("automation.karate.worker.image=sha256:"+"a".repeat(64),"automation.karate.gateway.image=sha256:"+"b".repeat(64)).withUserConfiguration(KarateExecutionEngineConfiguration.class,ExecutionEngineRegistryImpl.class).run(context->assertThat(context).doesNotHaveBean(KarateEnginePlugin.class));
        new ApplicationContextRunner().withSystemProperties("automation.karate.worker.image=sha256:"+"a".repeat(64),"automation.karate.gateway.image=sha256:"+"b".repeat(64)).withPropertyValues("automation.runner.workspace.root=C:/safe-workspace").withUserConfiguration(KarateExecutionEngineConfiguration.class,ExecutionEngineRegistryImpl.class).run(context->{assertThat(context).hasSingleBean(KarateEnginePlugin.class);var registry=context.getBean(ExecutionEngineRegistry.class);assertThat(registry.resolve("karate","1.5.2").engine()).isSameAs(context.getBean(KarateEnginePlugin.class));});
    }
}
