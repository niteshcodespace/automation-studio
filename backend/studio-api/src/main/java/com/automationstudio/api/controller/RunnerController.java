package com.automationstudio.api.controller;

import com.automationstudio.api.dto.runner.RunnerHeartbeatRequest;
import com.automationstudio.api.dto.runner.RunnerHeartbeatResponse;
import com.automationstudio.api.dto.runner.RunnerLeaseRequest;
import com.automationstudio.api.dto.runner.RunnerLeaseResponse;
import com.automationstudio.api.mapper.RunnerMapper;
import com.automationstudio.api.service.ExecutionClaimService;
import com.automationstudio.api.service.ExecutionHeartbeatService;
import com.automationstudio.api.service.ExecutionReclaimService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// TODO(AS-019): Require an authenticated runner principal and bind runnerId to it.
@RestController
@RequestMapping("/api/v1/runners")
public class RunnerController {

    private final ExecutionClaimService claimService;
    private final ExecutionHeartbeatService heartbeatService;
    private final ExecutionReclaimService reclaimService;
    private final RunnerMapper mapper;

    public RunnerController(
            ExecutionClaimService claimService,
            ExecutionHeartbeatService heartbeatService,
            ExecutionReclaimService reclaimService,
            RunnerMapper mapper) {
        this.claimService = claimService;
        this.heartbeatService = heartbeatService;
        this.reclaimService = reclaimService;
        this.mapper = mapper;
    }

    @PostMapping("/claim")
    public ResponseEntity<RunnerLeaseResponse> claim(
            @Valid @RequestBody RunnerLeaseRequest request) {
        return claimService.claimNext(mapper.toClaimCommand(request))
                .map(mapper::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
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
