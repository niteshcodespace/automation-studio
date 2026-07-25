package com.automationstudio.api.service;

import com.automationstudio.api.service.command.ClaimExecutionCommand;
import com.automationstudio.api.service.result.ClaimedExecution;
import java.util.Optional;

public interface ExecutionClaimService {

    Optional<ClaimedExecution> claimNext(ClaimExecutionCommand command);
}
