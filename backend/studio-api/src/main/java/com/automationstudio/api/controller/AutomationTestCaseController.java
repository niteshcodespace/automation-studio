package com.automationstudio.api.controller;

import com.automationstudio.api.domain.AutomationTestCaseStatus;
import com.automationstudio.api.dto.automation.testcase.AutomationTestCaseOrderRequest;
import com.automationstudio.api.dto.automation.testcase.AutomationTestCaseResponse;
import com.automationstudio.api.dto.automation.testcase.AutomationTestCaseStatusRequest;
import com.automationstudio.api.dto.automation.testcase.CreateAutomationTestCaseRequest;
import com.automationstudio.api.dto.automation.testcase.UpdateAutomationTestCaseRequest;
import com.automationstudio.api.entity.AutomationTestCase;
import com.automationstudio.api.mapper.AutomationTestCaseMapper;
import com.automationstudio.api.service.AutomationTestCaseService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
@RequestMapping("/api/v1/projects/{projectId}/automation-suites/{suiteId}/test-cases")
public class AutomationTestCaseController {

    private final AutomationTestCaseService service;
    private final AutomationTestCaseMapper mapper;

    public AutomationTestCaseController(
            AutomationTestCaseService service, AutomationTestCaseMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<AutomationTestCaseResponse> create(
            @PathVariable UUID projectId, @PathVariable UUID suiteId,
            @Valid @RequestBody CreateAutomationTestCaseRequest request) {
        AutomationTestCase saved = service.create(projectId, suiteId, mapper.toEntity(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(saved));
    }

    @GetMapping
    public ResponseEntity<Page<AutomationTestCaseResponse>> list(
            @PathVariable UUID projectId, @PathVariable UUID suiteId,
            @RequestParam(required = false) AutomationTestCaseStatus status,
            @PageableDefault(sort = {"position", "id"}) Pageable pageable) {
        return ResponseEntity.ok(service.list(projectId, suiteId, status, pageable)
                .map(mapper::toResponse));
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<AutomationTestCaseResponse> get(
            @PathVariable UUID projectId, @PathVariable UUID suiteId,
            @PathVariable UUID caseId) {
        return ResponseEntity.ok(mapper.toResponse(service.get(projectId, suiteId, caseId)));
    }

    @PutMapping("/{caseId}")
    public ResponseEntity<AutomationTestCaseResponse> update(
            @PathVariable UUID projectId, @PathVariable UUID suiteId,
            @PathVariable UUID caseId,
            @Valid @RequestBody UpdateAutomationTestCaseRequest request) {
        return ResponseEntity.ok(mapper.toResponse(
                service.update(projectId, suiteId, caseId, mapper.toEntity(request))));
    }

    @PatchMapping("/{caseId}/status")
    public ResponseEntity<AutomationTestCaseResponse> updateStatus(
            @PathVariable UUID projectId, @PathVariable UUID suiteId,
            @PathVariable UUID caseId,
            @Valid @RequestBody AutomationTestCaseStatusRequest request) {
        return ResponseEntity.ok(mapper.toResponse(
                service.updateStatus(projectId, suiteId, caseId, request.status())));
    }

    @DeleteMapping("/{caseId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId, @PathVariable UUID suiteId,
            @PathVariable UUID caseId) {
        service.delete(projectId, suiteId, caseId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/order")
    public ResponseEntity<List<AutomationTestCaseResponse>> reorder(
            @PathVariable UUID projectId, @PathVariable UUID suiteId,
            @Valid @RequestBody AutomationTestCaseOrderRequest request) {
        return ResponseEntity.ok(service.reorder(projectId, suiteId, request.caseIds()).stream()
                .map(mapper::toResponse).toList());
    }
}
