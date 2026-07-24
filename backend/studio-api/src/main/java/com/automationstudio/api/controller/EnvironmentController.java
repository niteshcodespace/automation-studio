package com.automationstudio.api.controller;

import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.EnvironmentType;
import com.automationstudio.api.dto.environment.CreateEnvironmentRequest;
import com.automationstudio.api.dto.environment.EnvironmentDefaultRequest;
import com.automationstudio.api.dto.environment.EnvironmentResponse;
import com.automationstudio.api.dto.environment.EnvironmentStatusRequest;
import com.automationstudio.api.dto.environment.UpdateEnvironmentRequest;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.mapper.EnvironmentMapper;
import com.automationstudio.api.service.EnvironmentService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/environments")
public class EnvironmentController {

    private final EnvironmentService service;
    private final EnvironmentMapper mapper;

    public EnvironmentController(EnvironmentService service, EnvironmentMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<EnvironmentResponse> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateEnvironmentRequest request,
            UriComponentsBuilder uriBuilder) {
        Environment saved = service.create(projectId, mapper.toCommand(request));
        URI location = uriBuilder.path("/api/v1/projects/{projectId}/environments/{environmentId}")
                .buildAndExpand(projectId, saved.getId())
                .toUri();
        return ResponseEntity.created(location).body(mapper.toResponse(saved));
    }

    @GetMapping
    public ResponseEntity<Page<EnvironmentResponse>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) EnvironmentStatus status,
            @RequestParam(required = false) EnvironmentType type,
            @RequestParam(required = false) Boolean isDefault,
            @PageableDefault(sort = {"name", "id"}) Pageable pageable) {
        return ResponseEntity.ok(service.list(projectId, status, type, isDefault, pageable)
                .map(mapper::toResponse));
    }

    @GetMapping("/{environmentId}")
    public ResponseEntity<EnvironmentResponse> get(
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId) {
        return ResponseEntity.ok(mapper.toResponse(service.get(projectId, environmentId)));
    }

    @PutMapping("/{environmentId}")
    public ResponseEntity<EnvironmentResponse> update(
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateEnvironmentRequest request) {
        long expectedVersion = IfMatchVersionParser.parse(ifMatch);
        return ResponseEntity.ok(mapper.toResponse(service.update(
                projectId, environmentId, expectedVersion, mapper.toCommand(request))));
    }

    @PatchMapping("/{environmentId}/status")
    public ResponseEntity<EnvironmentResponse> changeStatus(
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody EnvironmentStatusRequest request) {
        return ResponseEntity.ok(mapper.toResponse(service.changeStatus(
                projectId, environmentId, IfMatchVersionParser.parse(ifMatch), request.status())));
    }

    @PatchMapping("/{environmentId}/default")
    public ResponseEntity<EnvironmentResponse> changeDefault(
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody EnvironmentDefaultRequest request) {
        return ResponseEntity.ok(mapper.toResponse(service.changeDefault(
                projectId, environmentId, IfMatchVersionParser.parse(ifMatch),
                request.isDefault())));
    }

    @DeleteMapping("/{environmentId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        service.delete(projectId, environmentId, IfMatchVersionParser.parse(ifMatch));
        return ResponseEntity.noContent().build();
    }
}
