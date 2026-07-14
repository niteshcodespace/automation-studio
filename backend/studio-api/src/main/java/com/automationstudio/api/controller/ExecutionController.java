package com.automationstudio.api.controller;

import com.automationstudio.api.dto.CreateExecutionRequest;
import com.automationstudio.api.dto.ExecutionResponse;
import com.automationstudio.api.dto.ExecutionSummaryResponse;
import com.automationstudio.api.service.ExecutionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/executions")
public class ExecutionController {

    private final ExecutionService executionService;

    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @PostMapping
    public ResponseEntity<ExecutionResponse> create(@Valid @RequestBody CreateExecutionRequest request) {
        ExecutionResponse response = executionService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/executions/" + response.id())).body(response);
    }

    @GetMapping("/{executionId}")
    public ExecutionResponse get(@PathVariable UUID executionId) {
        return executionService.get(executionId);
    }

    @GetMapping
    public Page<ExecutionSummaryResponse> list(Pageable pageable) {
        return executionService.list(pageable);
    }
}
