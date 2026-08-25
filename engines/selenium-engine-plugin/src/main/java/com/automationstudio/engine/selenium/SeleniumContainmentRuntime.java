package com.automationstudio.engine.selenium;

import com.automationstudio.engine.sdk.ExecutionControl;
import java.util.UUID;

interface SeleniumContainmentRuntime {
    SeleniumAbsenceReport qualify(UUID executionId, ExecutionControl control);
}
