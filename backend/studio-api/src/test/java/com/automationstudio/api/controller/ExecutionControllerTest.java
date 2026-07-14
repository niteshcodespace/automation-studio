package com.automationstudio.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.dto.ExecutionResponse;
import com.automationstudio.api.dto.ExecutionSummaryResponse;
import com.automationstudio.api.exception.GlobalExceptionHandler;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.service.ExecutionService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ExecutionControllerTest {

    @Mock
    private ExecutionService executionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ExecutionController(executionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @Test
    void createsExecutionAndReturnsLocation() throws Exception {
        UUID executionId = UUID.randomUUID();
        when(executionService.create(any())).thenReturn(response(executionId));

        mockMvc.perform(post("/api/v1/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/executions/" + executionId))
                .andExpect(jsonPath("$.id").value(executionId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void rejectsInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/v1/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestedBy\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Request validation failed"))
                .andExpect(jsonPath("$.errors.projectId").exists())
                .andExpect(jsonPath("$.errors.environmentId").exists())
                .andExpect(jsonPath("$.errors.testSuiteId").exists())
                .andExpect(jsonPath("$.errors.requestedBy").exists());
    }

    @Test
    void returnsNotFoundProblemForMissingExecution() throws Exception {
        UUID executionId = UUID.randomUUID();
        when(executionService.get(executionId))
                .thenThrow(new ResourceNotFoundException("Execution", executionId));

        mockMvc.perform(get("/api/v1/executions/{executionId}", executionId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void returnsPaginatedExecutionSummaries() throws Exception {
        UUID executionId = UUID.randomUUID();
        ExecutionSummaryResponse summary = new ExecutionSummaryResponse(
                executionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ExecutionStatus.PENDING, "qa.user", OffsetDateTime.parse("2026-07-14T10:15:30Z"),
                null, null, null, null);
        when(executionService.list(any())).thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/executions").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(executionId.toString()))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.size").value(20));
    }

    private String validRequest() {
        return """
                {
                  "projectId": "%s",
                  "environmentId": "%s",
                  "testSuiteId": "%s",
                  "requestedBy": "qa.user"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private ExecutionResponse response(UUID id) {
        return new ExecutionResponse(
                id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ExecutionStatus.PENDING,
                "qa.user", OffsetDateTime.parse("2026-07-14T10:15:30Z"), null, null,
                null, null, null, null, null, null);
    }
}
