package com.automationstudio.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.automationstudio.api.domain.EnvironmentType;
import com.automationstudio.api.dto.environment.CreateEnvironmentRequest;
import com.automationstudio.api.dto.environment.UpdateEnvironmentRequest;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.exception.GlobalExceptionHandler;
import com.automationstudio.api.mapper.EnvironmentMapper;
import com.automationstudio.api.service.EnvironmentService;
import com.automationstudio.api.service.command.CreateEnvironmentCommand;
import com.automationstudio.api.service.command.UpdateEnvironmentCommand;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EnvironmentController.class)
@Import(GlobalExceptionHandler.class)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "logging.level.org.springframework.web.servlet.mvc.method.annotation"
                + ".RequestResponseBodyMethodProcessor=TRACE"
})
class EnvironmentRequestLoggingTest {

    private static final UUID PROJECT_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ENVIRONMENT_ID =
            UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final String CONFIGURATION_CANARY = "CONFIGURATION-CANARY-DO-NOT-LOG";
    private static final String SECRET_REFERENCE_CANARY =
            "SECRET-REFERENCE-CANARY-DO-NOT-LOG";
    private static final String NAME_CANARY = "NAME-CANARY-DO-NOT-LOG";
    private static final String DESCRIPTION_CANARY = "DESCRIPTION-CANARY-DO-NOT-LOG";
    private static final String URL_CANARY = "url-canary-do-not-log.example.test";
    private static final String MAP_KEY_CANARY = "MAP-KEY-CANARY-DO-NOT-LOG";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EnvironmentService service;

    @MockitoBean
    private EnvironmentMapper mapper;

    @Test
    void createRequestToStringIsFullyRedacted() {
        CreateEnvironmentRequest request = new CreateEnvironmentRequest(
                NAME_CANARY, DESCRIPTION_CANARY, "https://" + URL_CANARY,
                EnvironmentType.TEST,
                Map.of(MAP_KEY_CANARY, CONFIGURATION_CANARY),
                Map.of(MAP_KEY_CANARY, SECRET_REFERENCE_CANARY),
                null, null);

        assertFullyRedacted(request.toString(), "CreateEnvironmentRequest[redacted]");
    }

    @Test
    void updateRequestToStringIsFullyRedacted() {
        UpdateEnvironmentRequest request = new UpdateEnvironmentRequest(
                NAME_CANARY, DESCRIPTION_CANARY, "https://" + URL_CANARY,
                EnvironmentType.TEST,
                Map.of(MAP_KEY_CANARY, CONFIGURATION_CANARY),
                Map.of(MAP_KEY_CANARY, SECRET_REFERENCE_CANARY));

        assertFullyRedacted(request.toString(), "UpdateEnvironmentRequest[redacted]");
    }

    @Test
    void springMvcTraceLogsRedactedCreateAndUpdateWhileRequestsStillReachService(
            CapturedOutput output) throws Exception {
        Environment saved = new Environment();
        CreateEnvironmentCommand createCommand = new CreateEnvironmentCommand(
                "Canary", null, "https://canary.example.test",
                EnvironmentType.TEST, Map.of(), Map.of(), null, null);
        UpdateEnvironmentCommand updateCommand = new UpdateEnvironmentCommand(
                "Canary", null, "https://canary.example.test",
                EnvironmentType.TEST, Map.of(), Map.of());
        when(mapper.toCommand(any(CreateEnvironmentRequest.class))).thenReturn(createCommand);
        when(mapper.toCommand(any(UpdateEnvironmentRequest.class))).thenReturn(updateCommand);
        when(service.create(PROJECT_ID, createCommand)).thenReturn(saved);
        when(service.update(PROJECT_ID, ENVIRONMENT_ID, 0, updateCommand)).thenReturn(saved);

        String requestBody = requestBody();
        mockMvc.perform(post(environmentsPath()).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());
        mockMvc.perform(put(environmentsPath() + "/" + ENVIRONMENT_ID)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isOk());

        verify(service).create(PROJECT_ID, createCommand);
        verify(service).update(PROJECT_ID, ENVIRONMENT_ID, 0, updateCommand);
        assertThat(output.getOut())
                .contains("RequestResponseBodyMethodProcessor")
                .contains("CreateEnvironmentRequest[redacted]")
                .contains("UpdateEnvironmentRequest[redacted]")
                .doesNotContain(CONFIGURATION_CANARY, SECRET_REFERENCE_CANARY,
                        NAME_CANARY, DESCRIPTION_CANARY, URL_CANARY, MAP_KEY_CANARY);
    }

    @Test
    void malformedJsonLoggingDoesNotEchoRawPayload(CapturedOutput output) throws Exception {
        mockMvc.perform(post(environmentsPath()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + NAME_CANARY + "\",\"configuration\":{\""
                                + MAP_KEY_CANARY + "\":\"" + CONFIGURATION_CANARY + "\"}"))
                .andExpect(status().isBadRequest());

        assertThat(output.getOut()).doesNotContain(
                CONFIGURATION_CANARY, NAME_CANARY, MAP_KEY_CANARY);
    }

    private void assertFullyRedacted(String text, String expected) {
        assertThat(text)
                .isEqualTo(expected)
                .doesNotContain(CONFIGURATION_CANARY, SECRET_REFERENCE_CANARY,
                        NAME_CANARY, DESCRIPTION_CANARY, URL_CANARY, MAP_KEY_CANARY);
    }

    private String requestBody() {
        return """
                {
                  "name":"%s",
                  "description":"%s",
                  "baseUrl":"https://%s",
                  "type":"TEST",
                  "configuration":{"%s":"%s"},
                  "secretReferences":{"token":"vault:%s"}
                }
                """.formatted(NAME_CANARY, DESCRIPTION_CANARY, URL_CANARY,
                MAP_KEY_CANARY, CONFIGURATION_CANARY, SECRET_REFERENCE_CANARY);
    }

    private String environmentsPath() {
        return "/api/v1/projects/" + PROJECT_ID + "/environments";
    }
}
