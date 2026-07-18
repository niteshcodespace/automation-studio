package com.automationstudio.api.controller;

import com.automationstudio.api.domain.AutomationSuiteStatus;
import com.automationstudio.api.dto.automation.suite.AutomationSuiteResponse;
import com.automationstudio.api.dto.automation.suite.AutomationSuiteStatusRequest;
import com.automationstudio.api.dto.automation.suite.CreateAutomationSuiteRequest;
import com.automationstudio.api.dto.automation.suite.UpdateAutomationSuiteRequest;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.mapper.AutomationSuiteMapper;
import com.automationstudio.api.service.AutomationSuiteService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/automation-suites")
public class AutomationSuiteController {

    private final AutomationSuiteService automationSuiteService;
    private final AutomationSuiteMapper automationSuiteMapper;

    public AutomationSuiteController(
            AutomationSuiteService automationSuiteService,
            AutomationSuiteMapper automationSuiteMapper) {
        this.automationSuiteService = automationSuiteService;
        this.automationSuiteMapper = automationSuiteMapper;
    }

    @PostMapping
    public ResponseEntity<AutomationSuiteResponse> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateAutomationSuiteRequest request) {
        AutomationSuite suite = automationSuiteMapper.toEntity(request);
        AutomationSuite savedSuite = automationSuiteService.create(projectId, suite);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(automationSuiteMapper.toResponse(savedSuite));
    }

    @GetMapping
    public ResponseEntity<Page<AutomationSuiteResponse>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) AutomationSuiteStatus status,
            Pageable pageable) {
        Page<AutomationSuiteResponse> response = automationSuiteService
                .list(projectId, status, pageable)
                .map(automationSuiteMapper::toResponse);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{suiteId}")
    public ResponseEntity<AutomationSuiteResponse> get(
            @PathVariable UUID projectId,
            @PathVariable UUID suiteId) {
        return ResponseEntity.ok(automationSuiteMapper.toResponse(
                automationSuiteService.get(projectId, suiteId)));
    }

    @PutMapping("/{suiteId}")
    public ResponseEntity<AutomationSuiteResponse> update(
            @PathVariable UUID projectId,
            @PathVariable UUID suiteId,
            @Valid @RequestBody UpdateAutomationSuiteRequest request) {
        AutomationSuite updates = automationSuiteMapper.toEntity(request);
        return ResponseEntity.ok(automationSuiteMapper.toResponse(
                automationSuiteService.update(projectId, suiteId, updates)));
    }

    @PatchMapping("/{suiteId}/status")
    public ResponseEntity<AutomationSuiteResponse> changeStatus(
            @PathVariable UUID projectId,
            @PathVariable UUID suiteId,
            @Valid @RequestBody AutomationSuiteStatusRequest request) {
        return ResponseEntity.ok(automationSuiteMapper.toResponse(
                automationSuiteService.changeStatus(projectId, suiteId, request.status())));
    }

    @DeleteMapping("/{suiteId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId,
            @PathVariable UUID suiteId) {
        automationSuiteService.delete(projectId, suiteId);
        return ResponseEntity.noContent().build();
    }
}
