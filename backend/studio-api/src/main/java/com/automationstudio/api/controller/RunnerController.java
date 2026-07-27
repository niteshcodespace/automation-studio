package com.automationstudio.api.controller;

import com.automationstudio.api.domain.RunnerHealth;
import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.dto.runner.RegisterRunnerRequest;
import com.automationstudio.api.dto.runner.RunnerHeartbeatRequest;
import com.automationstudio.api.dto.runner.RunnerHeartbeatResponse;
import com.automationstudio.api.dto.runner.RunnerLeaseRequest;
import com.automationstudio.api.dto.runner.RunnerLeaseResponse;
import com.automationstudio.api.dto.runner.RunnerResponse;
import com.automationstudio.api.dto.runner.RunnerRuntimeHeartbeatRequest;
import com.automationstudio.api.dto.runner.RunnerStatusRequest;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.exception.PreconditionRequiredException;
import com.automationstudio.api.mapper.RunnerMapper;
import com.automationstudio.api.service.ExecutionHeartbeatService;
import com.automationstudio.api.service.ExecutionReclaimService;
import com.automationstudio.api.service.RunnerHeartbeatService;
import com.automationstudio.api.service.RunnerManagementService;
import com.automationstudio.api.service.RunnerQueryService;
import com.automationstudio.api.service.RunnerRegistrationService;
import com.automationstudio.api.service.RunnerSchedulingService;
import com.automationstudio.api.service.command.RecordRunnerHeartbeatCommand;
import com.automationstudio.api.service.result.SchedulingResult;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.service.query.RunnerQueryFilter;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

// TODO(AS-019): Require an authenticated runner principal and bind runnerId to it.
@RestController
@RequestMapping("/api/v1/runners")
public class RunnerController {

    private final RunnerSchedulingService schedulingService;
    private final ExecutionHeartbeatService heartbeatService;
    private final ExecutionReclaimService reclaimService;
    private final RunnerRegistrationService registrationService;
    private final RunnerHeartbeatService runnerHeartbeatService;
    private final RunnerQueryService queryService;
    private final RunnerManagementService managementService;
    private final RunnerMapper mapper;

    public RunnerController(
            RunnerSchedulingService schedulingService,
            ExecutionHeartbeatService heartbeatService,
            ExecutionReclaimService reclaimService,
            RunnerRegistrationService registrationService,
            RunnerHeartbeatService runnerHeartbeatService,
            RunnerQueryService queryService,
            RunnerManagementService managementService,
            RunnerMapper mapper) {
        this.schedulingService = schedulingService;
        this.heartbeatService = heartbeatService;
        this.reclaimService = reclaimService;
        this.registrationService = registrationService;
        this.runnerHeartbeatService = runnerHeartbeatService;
        this.queryService = queryService;
        this.managementService = managementService;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<RunnerResponse> register(
            @Valid @RequestBody RegisterRunnerRequest request,
            UriComponentsBuilder uriBuilder) {
        Runner runner = registrationService.register(mapper.toCommand(request));
        RunnerResponse response = mapper.toResponse(queryService.get(runner.getId()));
        boolean created = runner.getVersion() == 0
                && runner.getRegisteredAt().equals(runner.getLastRegisteredAt());
        if (!created) {
            return ResponseEntity.ok(response);
        }
        URI location = uriBuilder.path("/api/v1/runners/{runnerId}")
                .buildAndExpand(runner.getId())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    public ResponseEntity<Page<RunnerResponse>> list(
            @RequestParam(required = false) RunnerStatus status,
            @RequestParam(required = false) RunnerHealth health,
            @RequestParam(required = false) Boolean available,
            @RequestParam(required = false) String capability,
            @RequestParam(required = false) String label,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        RunnerQueryFilter filter = new RunnerQueryFilter(
                status, health, available, capability, label);
        return ResponseEntity.ok(
                queryService.list(filter, pageable, direction, page, size)
                        .map(mapper::toResponse));
    }

    @GetMapping("/{runnerId}")
    public ResponseEntity<RunnerResponse> get(@PathVariable UUID runnerId) {
        return ResponseEntity.ok(mapper.toResponse(queryService.get(runnerId)));
    }

    @PostMapping("/{runnerId}/heartbeats")
    public ResponseEntity<RunnerResponse> recordRunnerHeartbeat(
            @PathVariable UUID runnerId,
            @Valid @RequestBody RunnerRuntimeHeartbeatRequest request) {
        runnerHeartbeatService.recordHeartbeat(
                runnerId, new RecordRunnerHeartbeatCommand(request.runnerKey()));
        return ResponseEntity.ok(mapper.toResponse(queryService.get(runnerId)));
    }

    @PatchMapping("/{runnerId}/status")
    public ResponseEntity<RunnerResponse> changeStatus(
            @PathVariable UUID runnerId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody RunnerStatusRequest request) {
        if (ifMatch == null) {
            throw new PreconditionRequiredException("If-Match header is required");
        }
        managementService.changeStatus(
                runnerId, IfMatchVersionParser.parse(ifMatch), request.status());
        return ResponseEntity.ok(mapper.toResponse(queryService.get(runnerId)));
    }

    @PostMapping("/claim")
    public ResponseEntity<RunnerLeaseResponse> claim(
            @Valid @RequestBody RunnerLeaseRequest request) {
        SchedulingResult result =
                schedulingService.scheduleNext(mapper.toScheduleCommand(request));
        return switch (result.outcome()) {
            case SCHEDULED -> ResponseEntity.ok(
                    mapper.toResponse(result.scheduledExecution().orElseThrow()));
            case NO_COMPATIBLE_EXECUTION -> ResponseEntity.noContent().build();
            case RUNNER_NOT_FOUND -> throw new ResourceNotFoundException(
                    "Runner was not found");
            case RUNNER_INELIGIBLE -> throw new ResourceConflictException(
                    "Runner is not eligible to schedule work");
            case CAPACITY_EXHAUSTED -> throw new ResourceConflictException(
                    "Runner scheduling capacity is exhausted");
        };
    }

    @PostMapping("/heartbeats")
    public ResponseEntity<RunnerHeartbeatResponse> heartbeat(
            @Valid @RequestBody RunnerHeartbeatRequest request) {
        return ResponseEntity.ok(mapper.toResponse(
                heartbeatService.renew(mapper.toHeartbeatCommand(request))));
    }

    @PostMapping("/reclaim")
    public ResponseEntity<RunnerLeaseResponse> reclaim(
            @Valid @RequestBody RunnerLeaseRequest request) {
        return reclaimService.reclaimNext(mapper.toReclaimCommand(request))
                .map(mapper::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
