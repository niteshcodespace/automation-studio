package com.automationstudio.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.automationstudio.api.domain.RunnerHealth;
import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.dto.runner.RunnerResponse;
import com.automationstudio.api.entity.Runner;
import com.automationstudio.api.exception.GlobalExceptionHandler;
import com.automationstudio.api.mapper.RunnerMapper;
import com.automationstudio.api.service.ExecutionHeartbeatService;
import com.automationstudio.api.service.ExecutionReclaimService;
import com.automationstudio.api.service.RunnerHeartbeatService;
import com.automationstudio.api.service.RunnerManagementService;
import com.automationstudio.api.service.RunnerQueryService;
import com.automationstudio.api.service.RunnerRegistrationService;
import com.automationstudio.api.service.RunnerSchedulingService;
import com.automationstudio.api.service.command.RegisterRunnerCommand;
import com.automationstudio.api.service.command.RecordRunnerHeartbeatCommand;
import com.automationstudio.api.service.query.RunnerQueryFilter;
import com.automationstudio.api.service.result.RunnerDetailsResult;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RunnerController.class)
@Import(GlobalExceptionHandler.class)
class RunnerManagementControllerTest {

    private static final String BASE_PATH = "/api/v1/runners";
    private static final UUID RUNNER_ID = UUID.randomUUID();
    private static final OffsetDateTime TIME =
            OffsetDateTime.parse("2026-07-26T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private RunnerSchedulingService schedulingService;
    @MockitoBean
    private ExecutionHeartbeatService executionHeartbeatService;
    @MockitoBean
    private ExecutionReclaimService reclaimService;
    @MockitoBean
    private RunnerRegistrationService registrationService;
    @MockitoBean
    private RunnerHeartbeatService runnerHeartbeatService;
    @MockitoBean
    private RunnerQueryService queryService;
    @MockitoBean
    private RunnerManagementService managementService;
    @MockitoBean
    private RunnerMapper mapper;

    @Test
    void registersNewRunnerWithLocationAndReturnsReregistrationAsOk() throws Exception {
        RegisterRunnerCommand command = command();
        Runner newRunner = runner(0, TIME);
        RunnerDetailsResult details = details(RunnerStatus.ACTIVE, 0);
        when(mapper.toCommand(any())).thenReturn(command);
        when(registrationService.register(command)).thenReturn(newRunner);
        when(queryService.get(RUNNER_ID)).thenReturn(details);
        when(mapper.toResponse(details)).thenReturn(response(RunnerStatus.ACTIVE, 0));

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost" + BASE_PATH + "/"
                        + RUNNER_ID))
                .andExpect(jsonPath("$.id").value(RUNNER_ID.toString()))
                .andExpect(jsonPath("$.health").value("ONLINE"));

        Runner existing = runner(1, TIME.plusMinutes(1));
        when(registrationService.register(command)).thenReturn(existing);
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson()))
                .andExpect(status().isOk());
    }

    @Test
    void delegatesUuidAndKeyHeartbeatIdentityToService() throws Exception {
        RunnerDetailsResult details = details(RunnerStatus.ACTIVE, 1);
        when(queryService.get(RUNNER_ID)).thenReturn(details);
        when(mapper.toResponse(details)).thenReturn(response(RunnerStatus.ACTIVE, 1));

        mockMvc.perform(post(BASE_PATH + "/" + RUNNER_ID + "/heartbeats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runnerKey\":\"RUNNER-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heartbeatCount").value(1));
        verify(runnerHeartbeatService).recordHeartbeat(
                RUNNER_ID, new RecordRunnerHeartbeatCommand("RUNNER-01"));
    }

    @Test
    void getsListsAndChangesStatusUsingDocumentedResourceRoutes() throws Exception {
        RunnerDetailsResult details = details(RunnerStatus.DISABLED, 4);
        when(queryService.get(RUNNER_ID)).thenReturn(details);
        when(queryService.list(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(java.util.List.of(details)));
        when(mapper.toResponse(details)).thenReturn(response(RunnerStatus.DISABLED, 4));

        mockMvc.perform(get(BASE_PATH + "/" + RUNNER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mockMvc.perform(get(BASE_PATH).param("status", "DISABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].runnerKey").value("runner-01"));
        mockMvc.perform(patch(BASE_PATH + "/" + RUNNER_ID + "/status")
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        verify(managementService).changeStatus(RUNNER_ID, 3, RunnerStatus.DISABLED);
    }

    @Test
    void delegatesCombinedDiscoveryFiltersAndRejectsMalformedTypedFilters()
            throws Exception {
        when(queryService.list(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(java.util.List.of()));

        mockMvc.perform(get(BASE_PATH)
                        .param("status", "ACTIVE")
                        .param("health", "ONLINE")
                        .param("available", "true")
                        .param("capability", "playwright-java")
                        .param("label", "linux")
                        .param("sort", "lastSeenAt")
                        .param("direction", "desc"))
                .andExpect(status().isOk());
        verify(queryService).list(
                org.mockito.ArgumentMatchers.eq(new RunnerQueryFilter(
                        RunnerStatus.ACTIVE,
                        RunnerHealth.ONLINE,
                        true,
                        "playwright-java",
                        "linux")),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("desc"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull());

        mockMvc.perform(get(BASE_PATH).param("health", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE_PATH).param("available", "not-boolean"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE_PATH).param("page", "not-a-number"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE_PATH).param("size", "not-a-number"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingIfMatchReturnsPreconditionRequired() throws Exception {
        mockMvc.perform(patch(BASE_PATH + "/" + RUNNER_ID + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.message").value("If-Match header is required"));
        verifyNoInteractions(managementService);
    }

    @Test
    void beanValidationRejectsInvalidRegistrationAndHeartbeatBodies() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE_PATH + "/" + RUNNER_ID + "/heartbeats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runnerKey\":\"invalid key\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(registrationService, runnerHeartbeatService);
    }

    private static Runner runner(long version, OffsetDateTime lastRegisteredAt) {
        Runner runner = new Runner(
                "runner-01", "Runner", null, "1.0.0", "runner.internal",
                "linux", "amd64", 4, Map.of(), Map.of(), RunnerStatus.ACTIVE, TIME);
        ReflectionTestUtils.setField(runner, "id", RUNNER_ID);
        ReflectionTestUtils.setField(runner, "version", version);
        ReflectionTestUtils.setField(runner, "lastRegisteredAt", lastRegisteredAt);
        return runner;
    }

    private static RegisterRunnerCommand command() {
        return new RegisterRunnerCommand(
                "runner-01", "Runner", null, "1.0.0", "runner.internal",
                "linux", "amd64", 4, Map.of(), Map.of());
    }

    private static RunnerDetailsResult details(RunnerStatus status, long heartbeatCount) {
        return new RunnerDetailsResult(
                RUNNER_ID, "runner-01", "Runner", null, "1.0.0", "runner.internal",
                "linux", "amd64", 4, Map.of(), Map.of(), status, RunnerHealth.ONLINE,
                status == RunnerStatus.ACTIVE, TIME, TIME, TIME, 0, heartbeatCount,
                heartbeatCount, TIME, TIME);
    }

    private static RunnerResponse response(RunnerStatus status, long heartbeatCount) {
        RunnerDetailsResult result = details(status, heartbeatCount);
        return new RunnerResponse(
                result.id(), result.runnerKey(), result.name(), result.description(),
                result.agentVersion(), result.hostname(), result.operatingSystem(),
                result.architecture(), result.maxConcurrency(), result.capabilities(),
                result.labels(), result.status(), result.health(),
                result.availableForDispatch(), result.registeredAt(),
                result.lastRegisteredAt(), result.lastSeenAt(), result.version(),
                result.heartbeatVersion(), result.heartbeatCount(), result.createdAt(),
                result.updatedAt());
    }

    private static String registrationJson() {
        return """
                {
                  "runnerKey":"runner-01",
                  "name":"Runner",
                  "agentVersion":"1.0.0",
                  "hostname":"runner.internal",
                  "operatingSystem":"linux",
                  "architecture":"amd64",
                  "maxConcurrency":4,
                  "capabilities":{},
                  "labels":{}
                }
                """;
    }
}
