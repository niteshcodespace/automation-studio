package com.automationstudio.api.controller;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.dto.execution.CreateExecutionRequest;
import com.automationstudio.api.dto.execution.CancelExecutionRequest;
import com.automationstudio.api.dto.execution.ExecutionResponse;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.mapper.ExecutionMapper;
import com.automationstudio.api.service.ExecutionService;
import com.automationstudio.api.service.command.CancelExecutionCommand;
import com.automationstudio.api.http.IfMatchHeaderParser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/executions")
public class ExecutionController {

    private final ExecutionService service;
    private final ExecutionMapper mapper;
    private final IfMatchHeaderParser ifMatchHeaderParser;

    public ExecutionController(
            ExecutionService service,
            ExecutionMapper mapper,
            IfMatchHeaderParser ifMatchHeaderParser) {
        this.service = service;
        this.mapper = mapper;
        this.ifMatchHeaderParser = ifMatchHeaderParser;
    }

    @PostMapping
    public ResponseEntity<ExecutionResponse> create(
            @PathVariable UUID projectId,
            @RequestHeader(value = "X-Requested-By", defaultValue = "anonymous") String requester,
            @Valid @RequestBody CreateExecutionRequest request,
            UriComponentsBuilder uriBuilder) {
        Execution execution = service.create(projectId, requester, mapper.toCommand(request));
        URI location = uriBuilder.path("/api/v1/projects/{projectId}/executions/{executionId}")
                .buildAndExpand(projectId, execution.getId())
                .toUri();
        return ResponseEntity.created(location).body(mapper.toResponse(execution));
    }

    @GetMapping("/{executionId}")
    public ResponseEntity<ExecutionResponse> get(
            @PathVariable UUID projectId, @PathVariable UUID executionId) {
        return ResponseEntity.ok(mapper.toResponse(service.get(projectId, executionId)));
    }

    @GetMapping
    public ResponseEntity<Page<ExecutionResponse>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) ExecutionStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(service.list(projectId, status, pageable).map(mapper::toResponse));
    }

    @PostMapping("/{executionId}/cancel")
    public ResponseEntity<ExecutionResponse> cancel(
            @PathVariable UUID projectId,
            @PathVariable UUID executionId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "X-Requested-By", defaultValue = "anonymous") String actor,
            @Valid @RequestBody(required = false) CancelExecutionRequest request) {
        long expectedVersion = ifMatchHeaderParser.parseRequired(ifMatch);
        String reason = request == null ? null : request.reason();
        Execution execution = service.cancel(
                projectId,
                executionId,
                expectedVersion,
                actor,
                new CancelExecutionCommand(reason));
        return ResponseEntity.ok(mapper.toResponse(execution));
    }
}
