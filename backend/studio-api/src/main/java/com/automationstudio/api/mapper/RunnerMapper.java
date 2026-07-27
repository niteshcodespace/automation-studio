package com.automationstudio.api.mapper;

import com.automationstudio.api.dto.runner.RunnerHeartbeatRequest;
import com.automationstudio.api.dto.runner.RunnerHeartbeatResponse;
import com.automationstudio.api.dto.runner.RunnerLeaseRequest;
import com.automationstudio.api.dto.runner.RunnerLeaseResponse;
import com.automationstudio.api.dto.runner.RegisterRunnerRequest;
import com.automationstudio.api.dto.runner.RunnerResponse;
import com.automationstudio.api.service.command.RegisterRunnerCommand;
import com.automationstudio.api.service.command.ReclaimExecutionLeaseCommand;
import com.automationstudio.api.service.command.RenewExecutionLeaseCommand;
import com.automationstudio.api.service.command.ScheduleExecutionCommand;
import com.automationstudio.api.service.result.ClaimedExecution;
import com.automationstudio.api.service.result.ReclaimedExecutionLease;
import com.automationstudio.api.service.result.RenewedExecutionLease;
import com.automationstudio.api.service.result.RunnerDetailsResult;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RunnerMapper {

    @Mapping(target = "runnerKey", source = "runnerId")
    ScheduleExecutionCommand toScheduleCommand(RunnerLeaseRequest request);

    @Mapping(target = "newRunnerId", source = "runnerId")
    ReclaimExecutionLeaseCommand toReclaimCommand(RunnerLeaseRequest request);

    @Mapping(target = "expectedLeaseVersion", source = "leaseVersion")
    RenewExecutionLeaseCommand toHeartbeatCommand(RunnerHeartbeatRequest request);

    RunnerLeaseResponse toResponse(ClaimedExecution result);

    RunnerLeaseResponse toResponse(ReclaimedExecutionLease result);

    RunnerHeartbeatResponse toResponse(RenewedExecutionLease result);

    RegisterRunnerCommand toCommand(RegisterRunnerRequest request);

    RunnerResponse toResponse(RunnerDetailsResult result);
}
