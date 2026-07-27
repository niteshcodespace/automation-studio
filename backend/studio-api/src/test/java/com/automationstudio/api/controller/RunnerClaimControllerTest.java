package com.automationstudio.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.automationstudio.api.domain.ExecutionSelectionMode;
import com.automationstudio.api.domain.ExecutionStatus;
import com.automationstudio.api.domain.SchedulingOutcome;
import com.automationstudio.api.dto.runner.RunnerLeaseResponse;
import com.automationstudio.api.exception.GlobalExceptionHandler;
import com.automationstudio.api.exception.SchedulingOperationException;
import com.automationstudio.api.mapper.RunnerMapper;
import com.automationstudio.api.service.ExecutionHeartbeatService;
import com.automationstudio.api.service.ExecutionReclaimService;
import com.automationstudio.api.service.RunnerHeartbeatService;
import com.automationstudio.api.service.RunnerManagementService;
import com.automationstudio.api.service.RunnerQueryService;
import com.automationstudio.api.service.RunnerRegistrationService;
import com.automationstudio.api.service.RunnerSchedulingService;
import com.automationstudio.api.service.command.ScheduleExecutionCommand;
import com.automationstudio.api.service.result.ClaimedExecution;
import com.automationstudio.api.service.result.SchedulingResult;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
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
class RunnerClaimControllerTest {

    private static final String PATH = "/api/v1/runners/claim";
    private static final UUID EXECUTION_ID = UUID.randomUUID();
    private static final UUID TOKEN = UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-27T12:00:00Z");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private RunnerSchedulingService schedulingService;
    @MockitoBean private ExecutionHeartbeatService heartbeatService;
    @MockitoBean private ExecutionReclaimService reclaimService;
    @MockitoBean private RunnerRegistrationService registrationService;
    @MockitoBean private RunnerHeartbeatService runnerHeartbeatService;
    @MockitoBean private RunnerQueryService queryService;
    @MockitoBean private RunnerManagementService managementService;
    @MockitoBean private RunnerMapper mapper;

    @Test
    void returnsScheduledLease() throws Exception {
        ScheduleExecutionCommand command =
                new ScheduleExecutionCommand("runner-1", Duration.ofMinutes(2));
        ClaimedExecution claimed = claimed();
        when(mapper.toScheduleCommand(any())).thenReturn(command);
        when(schedulingService.scheduleNext(command)).thenReturn(
                result(SchedulingOutcome.SCHEDULED, claimed));
        when(mapper.toResponse(claimed)).thenReturn(response());

        perform(validRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(EXECUTION_ID.toString()))
                .andExpect(jsonPath("$.claimToken").value(TOKEN.toString()))
                .andExpect(jsonPath("$.leaseExpiresAt").value("2026-07-27T12:02:00Z"));
    }

    @Test
    void returnsNoContentWhenNoCompatibleWorkExists() throws Exception {
        stubOutcome(SchedulingOutcome.NO_COMPATIBLE_EXECUTION);

        perform(validRequest()).andExpect(status().isNoContent());
    }

    @Test
    void mapsMissingRunnerToNotFound() throws Exception {
        stubOutcome(SchedulingOutcome.RUNNER_NOT_FOUND);

        perform(validRequest())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Runner was not found"));
    }

    @Test
    void mapsCapacityExhaustionToConflict() throws Exception {
        stubOutcome(SchedulingOutcome.CAPACITY_EXHAUSTED);

        perform(validRequest())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Runner scheduling capacity is exhausted"));
    }

    @Test
    void mapsIneligibleRunnerToConflict() throws Exception {
        stubOutcome(SchedulingOutcome.RUNNER_INELIGIBLE);

        perform(validRequest())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Runner is not eligible to schedule work"));
    }

    @Test
    void rejectsInvalidRequestBeforeCallingService() throws Exception {
        perform("{\"runnerId\":\" \",\"leaseDuration\":\"PT0S\"}")
                .andExpect(status().isBadRequest());

        verifyNoInteractions(schedulingService);
    }

    @Test
    void rejectsMalformedPayload() throws Exception {
        perform("{\"runnerId\":\"runner-1\",\"leaseDuration\":\"invalid\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Malformed or unreadable request body"));
    }

    @Test
    void sanitizesUnexpectedSchedulingFailure() throws Exception {
        when(mapper.toScheduleCommand(any())).thenReturn(
                new ScheduleExecutionCommand("runner-1", Duration.ofMinutes(2)));
        when(schedulingService.scheduleNext(any())).thenThrow(
                new SchedulingOperationException(
                        "Atomic execution scheduling failed",
                        new IllegalStateException("database detail")));

        perform(validRequest())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("database detail"))));
    }

    private void stubOutcome(SchedulingOutcome outcome) {
        when(mapper.toScheduleCommand(any())).thenReturn(
                new ScheduleExecutionCommand("runner-1", Duration.ofMinutes(2)));
        when(schedulingService.scheduleNext(any())).thenReturn(result(outcome, null));
    }

    private org.springframework.test.web.servlet.ResultActions perform(String content)
            throws Exception {
        return mockMvc.perform(post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(content));
    }

    private static SchedulingResult result(
            SchedulingOutcome outcome, ClaimedExecution claimedExecution) {
        return new SchedulingResult(outcome, null, claimedExecution);
    }

    private static String validRequest() {
        return "{\"runnerId\":\"runner-1\",\"leaseDuration\":\"PT2M\"}";
    }

    private static ClaimedExecution claimed() {
        return new ClaimedExecution(
                EXECUTION_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ExecutionSelectionMode.SUITE,
                ExecutionStatus.CLAIMED,
                1,
                "runner-1",
                TOKEN,
                1,
                0,
                NOW,
                NOW.plusMinutes(2),
                Map.of("region", "eu"),
                Map.of("engineId", "playwright-java"),
                Map.of("selectionMode", "SUITE"));
    }

    private static RunnerLeaseResponse response() {
        ClaimedExecution claimed = claimed();
        return new RunnerLeaseResponse(
                claimed.executionId(),
                claimed.projectId(),
                claimed.environmentId(),
                claimed.automationSuiteId(),
                claimed.selectionMode(),
                claimed.status(),
                claimed.executionVersion(),
                claimed.runnerId(),
                claimed.claimToken(),
                claimed.leaseGeneration(),
                claimed.leaseVersion(),
                claimed.claimedAt(),
                claimed.leaseExpiresAt(),
                claimed.environmentSnapshot(),
                claimed.suiteSnapshot(),
                claimed.requestSnapshot());
    }
}
