package com.automationstudio.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.dto.runner.RunnerHeartbeatResponse;
import com.automationstudio.api.dto.runner.RunnerLeaseResponse;
import com.automationstudio.api.exception.GlobalExceptionHandler;
import com.automationstudio.api.mapper.RunnerMapper;
import com.automationstudio.api.service.ExecutionHeartbeatException;
import com.automationstudio.api.service.ExecutionHeartbeatService;
import com.automationstudio.api.service.ExecutionReclaimService;
import com.automationstudio.api.service.HeartbeatFailure;
import com.automationstudio.api.service.RunnerHeartbeatService;
import com.automationstudio.api.service.RunnerManagementService;
import com.automationstudio.api.service.RunnerQueryService;
import com.automationstudio.api.service.RunnerRegistrationService;
import com.automationstudio.api.service.RunnerSchedulingService;
import com.automationstudio.api.service.command.ScheduleExecutionCommand;
import com.automationstudio.api.service.command.ReclaimExecutionLeaseCommand;
import com.automationstudio.api.service.command.RenewExecutionLeaseCommand;
import com.automationstudio.api.service.result.ClaimedExecution;
import com.automationstudio.api.service.result.ReclaimedExecutionLease;
import com.automationstudio.api.service.result.RenewedExecutionLease;
import com.automationstudio.api.service.result.SchedulingResult;
import com.automationstudio.api.domain.SchedulingOutcome;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RunnerController.class)
@Import(GlobalExceptionHandler.class)
class RunnerControllerTest {

    private static final String BASE_PATH = "/api/v1/runners";
    private static final UUID EXECUTION_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID ENVIRONMENT_ID = UUID.randomUUID();
    private static final UUID SUITE_ID = UUID.randomUUID();
    private static final UUID TOKEN = UUID.randomUUID();
    private static final OffsetDateTime TIME =
            OffsetDateTime.parse("2026-07-26T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private RunnerSchedulingService schedulingService;
    @MockitoBean
    private ExecutionHeartbeatService heartbeatService;
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
    void returnsClaimWithTokenLeaseVersionAndSanitizedSnapshots() throws Exception {
        ScheduleExecutionCommand command =
                new ScheduleExecutionCommand("runner-1", Duration.ofMinutes(2));
        ClaimedExecution result = claimed();
        when(mapper.toScheduleCommand(any())).thenReturn(command);
        when(schedulingService.scheduleNext(command)).thenReturn(
                new SchedulingResult(SchedulingOutcome.SCHEDULED, null, result));
        when(mapper.toResponse(result)).thenReturn(leaseResponse(1, 0));

        mockMvc.perform(post(BASE_PATH + "/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseRequest("runner-1", "PT2M")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(EXECUTION_ID.toString()))
                .andExpect(jsonPath("$.claimToken").value(TOKEN.toString()))
                .andExpect(jsonPath("$.leaseGeneration").value(1))
                .andExpect(jsonPath("$.leaseVersion").value(0))
                .andExpect(jsonPath("$.environmentSnapshot.region").value("eu"))
                .andExpect(jsonPath("$.suiteSnapshot.engine").value("PLAYWRIGHT"))
                .andExpect(jsonPath("$.requestSnapshot.selectionMode").value("SUITE"));
    }

    @Test
    void returnsHeartbeatWithoutEchoingClaimToken() throws Exception {
        RenewExecutionLeaseCommand command = new RenewExecutionLeaseCommand(
                EXECUTION_ID, "runner-1", TOKEN, 1, 0, Duration.ofMinutes(2));
        RenewedExecutionLease result = new RenewedExecutionLease(
                EXECUTION_ID, "runner-1", 1, 1, TIME, TIME.plusMinutes(2));
        when(mapper.toHeartbeatCommand(any())).thenReturn(command);
        when(heartbeatService.renew(command)).thenReturn(result);
        when(mapper.toResponse(result)).thenReturn(new RunnerHeartbeatResponse(
                EXECUTION_ID, 1, 1, TIME, TIME.plusMinutes(2)));

        mockMvc.perform(post(BASE_PATH + "/heartbeats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "executionId":"%s",
                                  "runnerId":"runner-1",
                                  "claimToken":"%s",
                                  "leaseGeneration":1,
                                  "leaseVersion":0,
                                  "leaseDuration":"PT2M"
                                }
                                """.formatted(EXECUTION_ID, TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseGeneration").value(1))
                .andExpect(jsonPath("$.leaseVersion").value(1))
                .andExpect(jsonPath("$.claimToken").doesNotExist())
                .andExpect(jsonPath("$.runnerId").doesNotExist());
    }

    @Test
    void returnsReclaimedOwnershipAndDispatchSnapshot() throws Exception {
        ReclaimExecutionLeaseCommand command =
                new ReclaimExecutionLeaseCommand("runner-2", Duration.ofMinutes(2));
        ReclaimedExecutionLease result = reclaimed();
        when(mapper.toReclaimCommand(any())).thenReturn(command);
        when(reclaimService.reclaimNext(command)).thenReturn(Optional.of(result));
        when(mapper.toResponse(result)).thenReturn(leaseResponse(2, 3));

        mockMvc.perform(post(BASE_PATH + "/reclaim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseRequest("runner-2", "PT2M")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimToken").value(TOKEN.toString()))
                .andExpect(jsonPath("$.leaseGeneration").value(2))
                .andExpect(jsonPath("$.leaseVersion").value(3))
                .andExpect(jsonPath("$.requestSnapshot.selectionMode").value("SUITE"));
    }

    @Test
    void returnsNoContentWhenClaimOrReclaimHasNoWork() throws Exception {
        when(mapper.toScheduleCommand(any())).thenReturn(
                new ScheduleExecutionCommand("runner", Duration.ofMinutes(2)));
        when(schedulingService.scheduleNext(any())).thenReturn(new SchedulingResult(
                SchedulingOutcome.NO_COMPATIBLE_EXECUTION, null, null));
        when(mapper.toReclaimCommand(any())).thenReturn(
                new ReclaimExecutionLeaseCommand("runner", Duration.ofMinutes(2)));

        mockMvc.perform(post(BASE_PATH + "/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseRequest("runner", "PT2M")))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(BASE_PATH + "/reclaim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leaseRequest("runner", "PT2M")))
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectsBlankOversizedMissingAndNonpositiveInputs() throws Exception {
        for (String content : java.util.List.of(
                "{}",
                leaseRequest(" ", "PT2M"),
                leaseRequest("x".repeat(151), "PT2M"),
                leaseRequest("runner", "PT0S"),
                leaseRequest("runner", "-PT1S"))) {
            mockMvc.perform(post(BASE_PATH + "/claim")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(content))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post(BASE_PATH + "/heartbeats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runnerId":"runner","claimToken":"%s",
                                 "leaseGeneration":0,"leaseVersion":-1,
                                 "leaseDuration":"PT0S"}
                                """.formatted(TOKEN)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(schedulingService, heartbeatService, reclaimService);
    }

    @Test
    void rejectsMalformedUuidDurationAndJson() throws Exception {
        for (String content : java.util.List.of(
                "{\"runnerId\":\"runner\",\"leaseDuration\":\"not-a-duration\"}",
                "{\"runnerId\":\"runner\"",
                """
                {"executionId":"not-a-uuid","runnerId":"runner","claimToken":"%s",
                 "leaseGeneration":1,"leaseVersion":0,"leaseDuration":"PT2M"}
                """.formatted(TOKEN))) {
            String path = content.contains("executionId")
                    ? BASE_PATH + "/heartbeats"
                    : BASE_PATH + "/claim";
            mockMvc.perform(post(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(content))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message")
                            .value("Malformed or unreadable request body"));
        }
    }

    @Test
    void mapsOwnershipExpiryLifecycleAndOptimisticFailuresToConflict()
            throws Exception {
        when(mapper.toHeartbeatCommand(any())).thenReturn(new RenewExecutionLeaseCommand(
                EXECUTION_ID, "runner", TOKEN, 1, 0, Duration.ofMinutes(2)));
        for (HeartbeatFailure failure : java.util.List.of(
                HeartbeatFailure.OWNERSHIP_MISMATCH,
                HeartbeatFailure.STALE_GENERATION,
                HeartbeatFailure.EXPIRED_LEASE,
                HeartbeatFailure.EXECUTION_STATE_INELIGIBLE,
                HeartbeatFailure.OPTIMISTIC_LOCK_CONFLICT)) {
            doThrow(new ExecutionHeartbeatException(failure, "Lease conflict"))
                    .when(heartbeatService).renew(any());
            mockMvc.perform(post(BASE_PATH + "/heartbeats")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validHeartbeatRequest()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Lease conflict"));
        }
    }

    @Test
    void ownershipConflictIsSanitizedAndDoesNotIdentifyRunnerOrTokenMismatch()
            throws Exception {
        when(mapper.toHeartbeatCommand(any())).thenReturn(new RenewExecutionLeaseCommand(
                EXECUTION_ID, "runner", TOKEN, 1, 0, Duration.ofMinutes(2)));
        when(heartbeatService.renew(any())).thenThrow(new ExecutionHeartbeatException(
                HeartbeatFailure.OWNERSHIP_MISMATCH,
                "Execution lease ownership credentials do not match"));

        mockMvc.perform(post(BASE_PATH + "/heartbeats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHeartbeatRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Execution lease ownership credentials do not match"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("runner ID"))))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("claim token"))))
                .andExpect(jsonPath("$.claimToken").doesNotExist());
    }

    @Test
    void mapsMissingLeaseToNotFoundWithoutTokenDisclosure() throws Exception {
        when(mapper.toHeartbeatCommand(any())).thenReturn(new RenewExecutionLeaseCommand(
                EXECUTION_ID, "runner", TOKEN, 1, 0, Duration.ofMinutes(2)));
        when(heartbeatService.renew(any())).thenThrow(new ExecutionHeartbeatException(
                HeartbeatFailure.LEASE_NOT_FOUND, "Execution lease was not found"));

        mockMvc.perform(post(BASE_PATH + "/heartbeats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHeartbeatRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Execution lease was not found"))
                .andExpect(jsonPath("$.claimToken").doesNotExist());
    }

    private static String leaseRequest(String runnerId, String duration) {
        return """
                {"runnerId":"%s","leaseDuration":"%s"}
                """.formatted(runnerId, duration);
    }

    private static String validHeartbeatRequest() {
        return """
                {"executionId":"%s","runnerId":"runner","claimToken":"%s",
                 "leaseGeneration":1,"leaseVersion":0,"leaseDuration":"PT2M"}
                """.formatted(EXECUTION_ID, TOKEN);
    }

    private static ClaimedExecution claimed() {
        return new ClaimedExecution(
                EXECUTION_ID, PROJECT_ID, ENVIRONMENT_ID, SUITE_ID,
                ExecutionSelectionMode.SUITE, ExecutionStatus.CLAIMED, 1,
                "runner-1", TOKEN, 1, 0, TIME, TIME.plusMinutes(2),
                Map.of("region", "eu"), Map.of("engine", "PLAYWRIGHT"),
                Map.of("selectionMode", "SUITE"));
    }

    private static ReclaimedExecutionLease reclaimed() {
        return new ReclaimedExecutionLease(
                EXECUTION_ID, PROJECT_ID, ENVIRONMENT_ID, SUITE_ID,
                ExecutionSelectionMode.SUITE, ExecutionStatus.CLAIMED, 1,
                "runner-2", TOKEN, 2, 3, TIME, TIME, TIME.plusMinutes(2),
                Map.of("region", "eu"), Map.of("engine", "PLAYWRIGHT"),
                Map.of("selectionMode", "SUITE"));
    }

    private static RunnerLeaseResponse leaseResponse(long generation, long version) {
        return new RunnerLeaseResponse(
                EXECUTION_ID, PROJECT_ID, ENVIRONMENT_ID, SUITE_ID,
                ExecutionSelectionMode.SUITE, ExecutionStatus.CLAIMED, 1,
                generation == 1 ? "runner-1" : "runner-2", TOKEN,
                generation, version, TIME, TIME.plusMinutes(2),
                Map.of("region", "eu"), Map.of("engine", "PLAYWRIGHT"),
                Map.of("selectionMode", "SUITE"));
    }
}
