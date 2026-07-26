package com.automationstudio.api.mapper;

import com.automationstudio.api.dto.runner.RunnerHeartbeatRequest;
import com.automationstudio.api.dto.runner.RunnerHeartbeatResponse;
import com.automationstudio.api.dto.runner.RunnerLeaseRequest;
import com.automationstudio.api.dto.runner.RunnerLeaseResponse;
import com.automationstudio.api.service.command.ClaimExecutionCommand;
import com.automationstudio.api.service.command.ReclaimExecutionLeaseCommand;
import com.automationstudio.api.service.command.RenewExecutionLeaseCommand;
import com.automationstudio.api.service.result.ClaimedExecution;
import com.automationstudio.api.service.result.ReclaimedExecutionLease;
import com.automationstudio.api.service.result.RenewedExecutionLease;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RunnerMapper {

    ClaimExecutionCommand toClaimCommand(RunnerLeaseRequest request);

    @Mapping(target = "newRunnerId", source = "runnerId")
    ReclaimExecutionLeaseCommand toReclaimCommand(RunnerLeaseRequest request);

    @Mapping(target = "expectedLeaseVersion", source = "leaseVersion")
    RenewExecutionLeaseCommand toHeartbeatCommand(RunnerHeartbeatRequest request);

    RunnerLeaseResponse toResponse(ClaimedExecution result);

    RunnerLeaseResponse toResponse(ReclaimedExecutionLease result);

    RunnerHeartbeatResponse toResponse(RenewedExecutionLease result);
}
