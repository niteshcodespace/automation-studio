package com.automationstudio.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.dto.execution.CreateExecutionRequest;
import com.automationstudio.api.dto.execution.ExecutionResponse;
import com.automationstudio.api.entity.Execution;
import com.automationstudio.api.exception.GlobalExceptionHandler;
import com.automationstudio.api.mapper.ExecutionMapper;
import com.automationstudio.api.service.ExecutionService;
import com.automationstudio.api.service.command.CreateExecutionCommand;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(ExecutionController.class)
@Import(GlobalExceptionHandler.class)
class ExecutionControllerTest {

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID EXECUTION_ID = UUID.randomUUID();
    private static final UUID ENVIRONMENT_ID = UUID.randomUUID();
    private static final UUID SUITE_ID = UUID.randomUUID();
    private static final String PATH = "/api/v1/projects/" + PROJECT_ID + "/executions";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ExecutionService service;

    @MockitoBean
    private ExecutionMapper mapper;

    @Test
    void createsExecutionAndReturnsLocation() throws Exception {
        CreateExecutionRequest request = new CreateExecutionRequest(
                ENVIRONMENT_ID, SUITE_ID, ExecutionSelectionMode.SUITE, null);
        CreateExecutionCommand command = new CreateExecutionCommand(
                ENVIRONMENT_ID, SUITE_ID, ExecutionSelectionMode.SUITE, null);
        Execution execution = execution();
        when(mapper.toCommand(request)).thenReturn(command);
        when(service.create(PROJECT_ID, "operator", command)).thenReturn(execution);
        when(mapper.toResponse(execution)).thenReturn(response());

        mockMvc.perform(post(PATH).header("X-Requested-By", "operator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location", "http://localhost" + PATH + "/" + EXECUTION_ID))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getsAndListsProjectScopedExecutions() throws Exception {
        Execution execution = execution();
        when(service.get(PROJECT_ID, EXECUTION_ID)).thenReturn(execution);
        when(service.list(eq(PROJECT_ID), eq(ExecutionStatus.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(execution)));
        when(mapper.toResponse(execution)).thenReturn(response());

        mockMvc.perform(get(PATH + "/" + EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(PROJECT_ID.toString()));
        mockMvc.perform(get(PATH).param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(EXECUTION_ID.toString()));

        verify(service).get(PROJECT_ID, EXECUTION_ID);
    }

    @Test
    void rejectsMissingRequiredCreateFields() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private Execution execution() throws ReflectiveOperationException {
        Execution execution = new Execution();
        Field id = Execution.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(execution, EXECUTION_ID);
        return execution;
    }

    private ExecutionResponse response() {
        return new ExecutionResponse(
                EXECUTION_ID, PROJECT_ID, ENVIRONMENT_ID, SUITE_ID,
                ExecutionSelectionMode.SUITE, ExecutionStatus.PENDING, "operator",
                OffsetDateTime.parse("2026-01-01T00:00:00Z"), null, null,
                null, null, null, null, null, null, 0, null, null);
    }
}
