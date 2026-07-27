package com.automationstudio.api.service;

import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.service.command.RegisterRunnerCommand;
import java.util.UUID;

public interface RunnerRegistrationService {

    Runner register(RegisterRunnerCommand command);

    Runner getRunner(UUID runnerId);

    Runner getRunner(String runnerKey);
}
