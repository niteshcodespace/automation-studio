package com.automationstudio.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.automationstudio.api.domain.AutomationSuiteStatus;
import com.automationstudio.api.domain.SuiteType;
import com.automationstudio.api.dto.automation.suite.AutomationSuiteResponse;
import com.automationstudio.api.dto.automation.suite.AutomationSuiteStatusRequest;
import com.automationstudio.api.dto.automation.suite.CreateAutomationSuiteRequest;
import com.automationstudio.api.dto.automation.suite.UpdateAutomationSuiteRequest;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.exception.DuplicateResourceException;
import com.automationstudio.api.exception.GlobalExceptionHandler;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.mapper.AutomationSuiteMapper;
import com.automationstudio.api.service.AutomationSuiteService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(AutomationSuiteController.class)
@Import(GlobalExceptionHandler.class)
class AutomationSuiteControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");
    private static final UUID SUITE_ID = UUID.fromString(
            "30000000-0000-0000-0000-000000000001");
    private static final String BASE_PATH =
            "/api/v1/projects/" + PROJECT_ID + "/automation-suites";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AutomationSuiteService automationSuiteService;

    @MockitoBean
    private AutomationSuiteMapper automationSuiteMapper;

    @Test
    void createReturns201AndMappedResponse() throws Exception {
        CreateAutomationSuiteRequest request = createRequest();
        AutomationSuite mapped = new AutomationSuite();
        AutomationSuite saved = new AutomationSuite();
        AutomationSuiteResponse response = response(AutomationSuiteStatus.ACTIVE);
        when(automationSuiteMapper.toEntity(request)).thenReturn(mapped);
        when(automationSuiteService.create(PROJECT_ID, mapped)).thenReturn(saved);
        when(automationSuiteMapper.toResponse(saved)).thenReturn(response);

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(SUITE_ID.toString()))
                .andExpect(jsonPath("$.projectId").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$.name").value("Checkout suite"))
                .andExpect(jsonPath("$.engineType").value("PLAYWRIGHT"))
                .andExpect(jsonPath("$.suiteReference").value("tests/checkout"))
                .andExpect(jsonPath("$.engineId").value("playwright-java"))
                .andExpect(jsonPath("$.suiteType").value("UI"))
                .andExpect(jsonPath("$.configuration.browser").value("chromium"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(2));

        verify(automationSuiteService).create(PROJECT_ID, mapped);
    }

    @Test
    void createAcceptsNullableTransitionalFieldsAndStatus() throws Exception {
        CreateAutomationSuiteRequest request = new CreateAutomationSuiteRequest(
                "Legacy suite", null, "PLAYWRIGHT", "tests/legacy",
                null, null, null, null);
        AutomationSuite mapped = new AutomationSuite();
        AutomationSuite saved = new AutomationSuite();
        when(automationSuiteMapper.toEntity(request)).thenReturn(mapped);
        when(automationSuiteService.create(PROJECT_ID, mapped)).thenReturn(saved);
        when(automationSuiteMapper.toResponse(saved)).thenReturn(response(AutomationSuiteStatus.ACTIVE));

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        verify(automationSuiteService).create(PROJECT_ID, mapped);
    }

    @Test
    void createRejectsBlankRequiredFieldsWithoutCallingCollaborators() throws Exception {
        String body = """
                {"name":" ","engineType":" ","suiteReference":" "}
                """;

        mockMvc.perform(post(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "engineType: Automation suite engine type must not be blank; "
                                + "name: Automation suite name must not be blank; "
                                + "suiteReference: Automation suite reference must not be blank"));

        verifyNoInteractions(automationSuiteService, automationSuiteMapper);
    }

    @Test
    void createRejectsEveryOverLimitField() throws Exception {
        CreateAutomationSuiteRequest request = new CreateAutomationSuiteRequest(
                "n".repeat(151), null, "e".repeat(51), "r".repeat(301),
                "i".repeat(101), null, null, null);

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "engineId: Automation suite engine ID must not exceed 100 characters; "
                                + "engineType: Automation suite engine type must not exceed 50 characters; "
                                + "name: Automation suite name must not exceed 150 characters; "
                                + "suiteReference: Automation suite reference must not exceed 300 characters"));

        verifyNoInteractions(automationSuiteService, automationSuiteMapper);
    }

    @Test
    void createMapsDuplicateAndMissingProjectErrors() throws Exception {
        CreateAutomationSuiteRequest request = createRequest();
        AutomationSuite mapped = new AutomationSuite();
        when(automationSuiteMapper.toEntity(request)).thenReturn(mapped);
        when(automationSuiteService.create(PROJECT_ID, mapped))
                .thenThrow(new DuplicateResourceException("duplicate"));

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("duplicate"));

        reset(automationSuiteService);
        when(automationSuiteService.create(PROJECT_ID, mapped))
                .thenThrow(new ResourceNotFoundException("project missing"));
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("project missing"));
    }

    @Test
    void getReturnsMappedResponseAndDelegatesWithBothIds() throws Exception {
        AutomationSuite suite = new AutomationSuite();
        when(automationSuiteService.get(PROJECT_ID, SUITE_ID)).thenReturn(suite);
        when(automationSuiteMapper.toResponse(suite)).thenReturn(response(AutomationSuiteStatus.ACTIVE));

        mockMvc.perform(get(suitePath())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SUITE_ID.toString()));

        verify(automationSuiteService).get(PROJECT_ID, SUITE_ID);
    }

    @Test
    void getMapsMissingOrCrossProjectSuiteTo404() throws Exception {
        when(automationSuiteService.get(PROJECT_ID, SUITE_ID))
                .thenThrow(new ResourceNotFoundException("suite missing"));

        mockMvc.perform(get(suitePath())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("suite missing"));
    }

    @Test
    void listWithoutStatusPreservesPaginationAndMapsContent() throws Exception {
        AutomationSuite suite = new AutomationSuite();
        AutomationSuiteResponse response = response(AutomationSuiteStatus.ACTIVE);
        Pageable expectedPageable = PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "name"));
        when(automationSuiteService.list(eq(PROJECT_ID), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(suite), expectedPageable, 6));
        when(automationSuiteMapper.toResponse(suite)).thenReturn(response);

        mockMvc.perform(get(BASE_PATH)
                        .param("page", "1").param("size", "5").param("sort", "name,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(SUITE_ID.toString()))
                .andExpect(jsonPath("$.totalElements").value(6));

        var pageableCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(automationSuiteService).list(eq(PROJECT_ID), eq(null), pageableCaptor.capture());
        Pageable actual = pageableCaptor.getValue();
        assertThat(actual.getPageNumber()).isEqualTo(1);
        assertThat(actual.getPageSize()).isEqualTo(5);
        assertThat(actual.getSort().getOrderFor("name").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void listAcceptsStatusFilterAndRejectsInvalidStatus() throws Exception {
        when(automationSuiteService.list(
                eq(PROJECT_ID), eq(AutomationSuiteStatus.ARCHIVED), any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get(BASE_PATH).param("status", "ARCHIVED"))
                .andExpect(status().isOk());
        verify(automationSuiteService).list(
                eq(PROJECT_ID), eq(AutomationSuiteStatus.ARCHIVED), any(Pageable.class));

        mockMvc.perform(get(BASE_PATH).param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateReturnsMappedResponseAndCannotControlStatus() throws Exception {
        UpdateAutomationSuiteRequest request = updateRequest();
        AutomationSuite updates = new AutomationSuite();
        AutomationSuite saved = new AutomationSuite();
        when(automationSuiteMapper.toEntity(request)).thenReturn(updates);
        when(automationSuiteService.update(PROJECT_ID, SUITE_ID, updates)).thenReturn(saved);
        when(automationSuiteMapper.toResponse(saved)).thenReturn(response(AutomationSuiteStatus.INACTIVE));

        String json = objectMapper.writeValueAsString(request);
        assertThat(json).doesNotContain("status");
        mockMvc.perform(put(suitePath()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        verify(automationSuiteService).update(PROJECT_ID, SUITE_ID, updates);
    }

    @Test
    void updateIgnoresStatusSuppliedAsAnUnknownJsonProperty() throws Exception {
        AutomationSuite updates = new AutomationSuite();
        AutomationSuite saved = new AutomationSuite();
        when(automationSuiteMapper.toEntity(any(UpdateAutomationSuiteRequest.class)))
                .thenReturn(updates);
        when(automationSuiteService.update(PROJECT_ID, SUITE_ID, updates)).thenReturn(saved);
        when(automationSuiteMapper.toResponse(saved))
                .thenReturn(response(AutomationSuiteStatus.ACTIVE));
        String body = """
                {
                  "name":"Checkout suite",
                  "description":"Updated tests",
                  "engineType":"PLAYWRIGHT",
                  "suiteReference":"tests/checkout",
                  "engineId":"playwright-java",
                  "suiteType":"UI",
                  "configuration":{"browser":"chromium"},
                  "status":"ARCHIVED"
                }
                """;

        mockMvc.perform(put(suitePath()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        ArgumentCaptor<AutomationSuite> suiteCaptor =
                ArgumentCaptor.forClass(AutomationSuite.class);
        verify(automationSuiteService).update(
                eq(PROJECT_ID), eq(SUITE_ID), suiteCaptor.capture());
        assertThat(suiteCaptor.getValue().getStatus()).isEqualTo(AutomationSuiteStatus.ACTIVE);
    }

    @Test
    void updateRejectsInvalidFieldsAndMapsConflictAndNotFound() throws Exception {
        mockMvc.perform(put(suitePath()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"engineType\":\" \",\"suiteReference\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(automationSuiteService, automationSuiteMapper);

        UpdateAutomationSuiteRequest request = updateRequest();
        AutomationSuite updates = new AutomationSuite();
        when(automationSuiteMapper.toEntity(request)).thenReturn(updates);
        when(automationSuiteService.update(PROJECT_ID, SUITE_ID, updates))
                .thenThrow(new DuplicateResourceException("duplicate"));
        mockMvc.perform(put(suitePath()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());

        reset(automationSuiteService);
        when(automationSuiteService.update(PROJECT_ID, SUITE_ID, updates))
                .thenThrow(new ResourceNotFoundException("missing"));
        mockMvc.perform(put(suitePath()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void changeStatusReturnsMappedResponseAndDelegatesExplicitStatus() throws Exception {
        AutomationSuiteStatusRequest request =
                new AutomationSuiteStatusRequest(AutomationSuiteStatus.ARCHIVED);
        AutomationSuite saved = new AutomationSuite();
        when(automationSuiteService.changeStatus(
                PROJECT_ID, SUITE_ID, AutomationSuiteStatus.ARCHIVED)).thenReturn(saved);
        when(automationSuiteMapper.toResponse(saved)).thenReturn(response(AutomationSuiteStatus.ARCHIVED));

        mockMvc.perform(patch(suitePath() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        verify(automationSuiteService).changeStatus(
                PROJECT_ID, SUITE_ID, AutomationSuiteStatus.ARCHIVED);
    }

    @Test
    void changeStatusRejectsNullAndInvalidValues() throws Exception {
        mockMvc.perform(patch(suitePath() + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":null}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(suitePath() + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(automationSuiteService, automationSuiteMapper);
    }

    @Test
    void deleteReturns204AndDelegatesBothIds() throws Exception {
        mockMvc.perform(delete(suitePath()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(automationSuiteService).delete(PROJECT_ID, SUITE_ID);
    }

    @Test
    void deleteMapsMissingSuiteTo404() throws Exception {
        doThrow(new ResourceNotFoundException("missing"))
                .when(automationSuiteService).delete(PROJECT_ID, SUITE_ID);

        mockMvc.perform(delete(suitePath())).andExpect(status().isNotFound());
    }

    @Test
    void invalidProjectOrSuiteUuidReturns400WithoutServiceCalls() throws Exception {
        mockMvc.perform(get("/api/v1/projects/not-a-uuid/automation-suites"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE_PATH + "/not-a-uuid"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(automationSuiteService);
    }

    @Test
    void invalidSuiteTypeReturns400WithoutServiceCall() throws Exception {
        String body = """
                {"name":"Suite","engineType":"PLAYWRIGHT","suiteReference":"tests/suite",
                 "suiteType":"UNKNOWN"}
                """;
        mockMvc.perform(post(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verify(automationSuiteService, never()).create(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"scalar\"", "42"})
    void createRejectsNonObjectConfiguration(String configuration) throws Exception {
        String body = """
                {"name":"Suite","engineType":"PLAYWRIGHT","suiteReference":"tests/suite",
                 "configuration":%s}
                """.formatted(configuration);

        mockMvc.perform(post(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(automationSuiteService, automationSuiteMapper);
    }

    @Test
    void createRejectsMalformedJson() throws Exception {
        String body = """
                {"name":"Suite","engineType":"PLAYWRIGHT","suiteReference":"tests/suite"
                """;

        mockMvc.perform(post(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(automationSuiteService, automationSuiteMapper);
    }

    private CreateAutomationSuiteRequest createRequest() {
        return new CreateAutomationSuiteRequest(
                "Checkout suite", "Critical checkout tests", "PLAYWRIGHT", "tests/checkout",
                "playwright-java", SuiteType.UI, Map.of("browser", "chromium"), null);
    }

    private UpdateAutomationSuiteRequest updateRequest() {
        return new UpdateAutomationSuiteRequest(
                "Checkout suite", "Updated tests", "PLAYWRIGHT", "tests/checkout",
                "playwright-java", SuiteType.UI, Map.of("browser", "chromium"));
    }

    private AutomationSuiteResponse response(AutomationSuiteStatus status) {
        return new AutomationSuiteResponse(
                SUITE_ID, PROJECT_ID, "Checkout suite", "Critical checkout tests",
                "PLAYWRIGHT", "tests/checkout", "playwright-java", SuiteType.UI,
                Map.of("browser", "chromium"), status, 2,
                OffsetDateTime.parse("2026-07-17T10:00:00Z"),
                OffsetDateTime.parse("2026-07-17T11:00:00Z"));
    }

    private String suitePath() {
        return BASE_PATH + "/" + SUITE_ID;
    }
}
