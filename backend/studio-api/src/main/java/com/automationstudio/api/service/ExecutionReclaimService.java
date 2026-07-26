package com.automationstudio.api.service;

import com.automationstudio.api.service.command.ReclaimExecutionLeaseCommand;
import com.automationstudio.api.service.result.ReclaimedExecutionLease;
import java.util.Optional;

public interface ExecutionReclaimService {

    Optional<ReclaimedExecutionLease> reclaimNext(ReclaimExecutionLeaseCommand command);
}
