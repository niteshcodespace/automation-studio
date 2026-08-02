package com.automationstudio.api.execution.engine.playwright.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightActionType;
import com.automationstudio.api.execution.engine.playwright.manifest.PlaywrightStep;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlaywrightActionExecutorRegistryTest {
    @Test
    void registersAndResolvesAllApprovedExecutorsDeterministically() {
        List<PlaywrightActionExecutor> caller = new ArrayList<>(approved());
        PlaywrightActionExecutorRegistry registry = new PlaywrightActionExecutorRegistry(caller);
        caller.clear();

        assertThat(registry.size()).isEqualTo(6);
        for (PlaywrightActionType type : PlaywrightActionType.values()) {
            assertThat(registry.resolve(type).actionType()).isEqualTo(type.manifestValue());
        }
    }

    @Test
    void rejectsNullBlankDuplicateAndUnsupportedRegistrations() {
        assertThatThrownBy(() -> new PlaywrightActionExecutorRegistry(null))
                .isInstanceOfSatisfying(PlaywrightActionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("INVALID_EXECUTOR_REGISTRATION");
                    assertThat(failure.getMessage())
                            .isEqualTo("Action executor registration is invalid")
                            .doesNotContain("null");
                });
        assertThatThrownBy(() -> new PlaywrightActionExecutorRegistry(Arrays.asList((PlaywrightActionExecutor) null)))
                .isInstanceOfSatisfying(PlaywrightActionException.class,
                        failure -> assertThat(failure.code()).isEqualTo("INVALID_EXECUTOR_REGISTRATION"));
        assertThatThrownBy(() -> new PlaywrightActionExecutorRegistry(List.of(stub(" "))))
                .isInstanceOf(PlaywrightActionException.class);
        assertThatThrownBy(() -> new PlaywrightActionExecutorRegistry(
                        List.of(new ClickActionExecutor(), new ClickActionExecutor())))
                .isInstanceOfSatisfying(PlaywrightActionException.class,
                        failure -> assertThat(failure.code()).isEqualTo("DUPLICATE_ACTION_EXECUTOR"));
        PlaywrightActionExecutorRegistry empty = new PlaywrightActionExecutorRegistry(List.of());
        assertThatThrownBy(() -> empty.resolve(PlaywrightActionType.CLICK))
                .isInstanceOfSatisfying(PlaywrightActionException.class,
                        failure -> assertThat(failure.code()).isEqualTo("UNSUPPORTED_ACTION"));
    }

    private List<PlaywrightActionExecutor> approved() {
        return List.of(
                new AssertUrlActionExecutor(), new FillActionExecutor(),
                new NavigateActionExecutor(), new AssertVisibleActionExecutor(),
                new ClickActionExecutor(), new AssertTextActionExecutor());
    }

    private PlaywrightActionExecutor stub(String identifier) {
        return new PlaywrightActionExecutor() {
            @Override public String actionType() { return identifier; }
            @Override public PlaywrightActionOutcome execute(
                    PlaywrightStep step, PlaywrightActionExecutionContext context) {
                throw new AssertionError("Not executed");
            }
        };
    }
}
