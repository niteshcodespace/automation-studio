package com.automationstudio.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.automationstudio.api.domain.AutomationTestCaseStatus;
import com.automationstudio.api.dto.automation.testcase.AutomationTestCaseResponse;
import com.automationstudio.api.dto.automation.testcase.CreateAutomationTestCaseRequest;
import com.automationstudio.api.dto.automation.testcase.UpdateAutomationTestCaseRequest;
import com.automationstudio.api.entity.AutomationTestCase;
import com.automationstudio.api.exception.DuplicateResourceException;
import com.automationstudio.api.exception.GlobalExceptionHandler;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.mapper.AutomationTestCaseMapper;
import com.automationstudio.api.service.AutomationTestCaseService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(AutomationTestCaseController.class)
@Import(GlobalExceptionHandler.class)
class AutomationTestCaseControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SUITE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID CASE_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_CASE_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final String PATH = "/api/v1/projects/" + PROJECT_ID
            + "/automation-suites/" + SUITE_ID + "/test-cases";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private AutomationTestCaseService service;
    @MockitoBean private AutomationTestCaseMapper mapper;

    @Test
    void createCompleteRequestDelegatesExactlyAndReturnsCompleteResponse() throws Exception {
        Map<String, Object> configuration = mixedConfiguration();
        CreateAutomationTestCaseRequest expectedRequest = new CreateAutomationTestCaseRequest(
                "Complete case", "Complete description", "complete-ref", configuration,
                AutomationTestCaseStatus.INACTIVE);
        AutomationTestCase mapped = new AutomationTestCase();
        AutomationTestCase persisted = new AutomationTestCase();
        AutomationTestCaseResponse expectedResponse = new AutomationTestCaseResponse(
                CASE_ID, SUITE_ID, "Complete case", "Complete description", "complete-ref",
                configuration, AutomationTestCaseStatus.INACTIVE, 4, 2,
                OffsetDateTime.parse("2026-07-19T10:00:00Z"),
                OffsetDateTime.parse("2026-07-19T11:00:00Z"));
        when(mapper.toEntity(expectedRequest)).thenReturn(mapped);
        when(service.create(PROJECT_ID, SUITE_ID, mapped)).thenReturn(persisted);
        when(mapper.toResponse(persisted)).thenReturn(expectedResponse);

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(expectedRequest)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(CASE_ID.toString()))
                .andExpect(jsonPath("$.automationSuiteId").value(SUITE_ID.toString()))
                .andExpect(jsonPath("$.name").value("Complete case"))
                .andExpect(jsonPath("$.description").value("Complete description"))
                .andExpect(jsonPath("$.caseReference").value("complete-ref"))
                .andExpect(jsonPath("$.configuration.nested.region").value("eu"))
                .andExpect(jsonPath("$.configuration.array[0]").value(1))
                .andExpect(jsonPath("$.configuration.array[1]").value("two"))
                .andExpect(jsonPath("$.configuration.array[2]").value(true))
                .andExpect(jsonPath("$.configuration.array[3]").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.configuration.string").value("value"))
                .andExpect(jsonPath("$.configuration.number").value(3))
                .andExpect(jsonPath("$.configuration.boolean").value(false))
                .andExpect(jsonPath("$.configuration.nullable").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.position").value(4))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.createdAt").value("2026-07-19T10:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-07-19T11:00:00Z"));

        ArgumentCaptor<CreateAutomationTestCaseRequest> captor =
                ArgumentCaptor.forClass(CreateAutomationTestCaseRequest.class);
        verify(mapper).toEntity(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("Complete case");
        assertThat(captor.getValue().description()).isEqualTo("Complete description");
        assertThat(captor.getValue().caseReference()).isEqualTo("complete-ref");
        assertThat(captor.getValue().status()).isEqualTo(AutomationTestCaseStatus.INACTIVE);
        assertMixedConfiguration(captor.getValue().configuration());
        verify(service).create(PROJECT_ID, SUITE_ID, mapped);
        verify(mapper).toResponse(persisted);
        verifyNoMoreInteractions(service, mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"Case\",\"caseReference\":\"case-ref\"}",
            "{\"name\":\"Case\",\"caseReference\":\"case-ref\",\"configuration\":null}"
    })
    void createAcceptsOmittedOrNullConfigurationAndStatusWithExactDelegation(String body)
            throws Exception {
        CreateAutomationTestCaseRequest expected =
                new CreateAutomationTestCaseRequest("Case", null, "case-ref", null, null);
        AutomationTestCase mapped = new AutomationTestCase();
        AutomationTestCase saved = new AutomationTestCase();
        when(mapper.toEntity(expected)).thenReturn(mapped);
        when(service.create(PROJECT_ID, SUITE_ID, mapped)).thenReturn(saved);
        when(mapper.toResponse(saved)).thenReturn(response(CASE_ID, 0));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(CASE_ID.toString()));

        ArgumentCaptor<CreateAutomationTestCaseRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateAutomationTestCaseRequest.class);
        verify(mapper, times(1)).toEntity(requestCaptor.capture());
        assertThat(requestCaptor.getValue().configuration()).isNull();
        assertThat(requestCaptor.getValue().status()).isNull();
        verify(service, times(1)).create(PROJECT_ID, SUITE_ID, mapped);
        verify(service, never()).create(eq(PROJECT_ID), eq(SUITE_ID), eq(null));
        verify(mapper, times(1)).toResponse(saved);
    }

    @ParameterizedTest
    @MethodSource("validObjectConfigurations")
    void createAcceptsEveryObjectConfiguration(String configuration, boolean mixed) throws Exception {
        AutomationTestCase mapped = new AutomationTestCase();
        AutomationTestCase persisted = new AutomationTestCase();
        when(mapper.toEntity(any(CreateAutomationTestCaseRequest.class))).thenReturn(mapped);
        when(service.create(PROJECT_ID, SUITE_ID, mapped)).thenReturn(persisted);
        when(mapper.toResponse(persisted)).thenReturn(response(CASE_ID, 0));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Case","caseReference":"case-ref","configuration":%s}
                """.formatted(configuration))).andExpect(status().isCreated());

        ArgumentCaptor<CreateAutomationTestCaseRequest> captor =
                ArgumentCaptor.forClass(CreateAutomationTestCaseRequest.class);
        verify(mapper).toEntity(captor.capture());
        assertThat(captor.getValue().configuration()).isNotNull();
        if (mixed) {
            assertMixedConfiguration(captor.getValue().configuration());
        } else {
            assertThat(captor.getValue().configuration()).isEmpty();
        }
        verify(service).create(PROJECT_ID, SUITE_ID, mapped);
        verify(mapper).toResponse(persisted);
    }

    @Test
    void createAcceptsExactLengthBoundaries() throws Exception {
        String name = "n".repeat(150);
        String caseReference = "r".repeat(300);
        CreateAutomationTestCaseRequest expected =
                new CreateAutomationTestCaseRequest(name, null, caseReference, null, null);
        AutomationTestCase mapped = new AutomationTestCase();
        AutomationTestCase persisted = new AutomationTestCase();
        when(mapper.toEntity(expected)).thenReturn(mapped);
        when(service.create(PROJECT_ID, SUITE_ID, mapped)).thenReturn(persisted);
        when(mapper.toResponse(persisted)).thenReturn(response(CASE_ID, 0));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"%s","caseReference":"%s"}
                """.formatted(name, caseReference)))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateAutomationTestCaseRequest> captor =
                ArgumentCaptor.forClass(CreateAutomationTestCaseRequest.class);
        verify(mapper).toEntity(captor.capture());
        assertThat(captor.getValue().name()).hasSize(150).isEqualTo(name);
        assertThat(captor.getValue().caseReference()).hasSize(300).isEqualTo(caseReference);
        verify(service).create(PROJECT_ID, SUITE_ID, mapped);
        verify(mapper).toResponse(persisted);
    }

    @ParameterizedTest
    @MethodSource("invalidCreateBodies")
    void createRejectsInvalidBodiesBeforeCollaboratorInvocation(String body) throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service, mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"scalar\"", "42", "true"})
    void createRejectsNonObjectConfigurationBeforeCollaboratorInvocation(String configuration)
            throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Case","caseReference":"case-ref","configuration":%s}
                """.formatted(configuration))).andExpect(status().isBadRequest());
        verifyNoInteractions(service, mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/projects/not-a-uuid/automation-suites/30000000-0000-0000-0000-000000000001/test-cases",
            "/api/v1/projects/20000000-0000-0000-0000-000000000001/automation-suites/not-a-uuid/test-cases"
    })
    void createRejectsInvalidPathUuid(String path) throws Exception {
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Case\",\"caseReference\":\"ref\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, mapper);
    }

    @ParameterizedTest
    @MethodSource("createServiceConflicts")
    void createMapsSupportedConflictsToStandard409(RuntimeException exception) throws Exception {
        CreateAutomationTestCaseRequest request =
                new CreateAutomationTestCaseRequest("Case", null, "ref", null, null);
        AutomationTestCase mapped = new AutomationTestCase();
        when(mapper.toEntity(request)).thenReturn(mapped);
        when(service.create(PROJECT_ID, SUITE_ID, mapped)).thenThrow(exception);

        assertApiError(mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))),
                409, "Conflict", exception.getMessage(), PATH);
        verify(mapper).toEntity(request);
        verify(service).create(PROJECT_ID, SUITE_ID, mapped);
        verify(mapper, never()).toResponse(any());
    }

    @Test
    void listForwardsExactDefaultPageableAndReturnsSpringPage() throws Exception {
        AutomationTestCase entity = new AutomationTestCase();
        when(service.list(eq(PROJECT_ID), eq(SUITE_ID), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity)));
        when(mapper.toResponse(entity)).thenReturn(response(CASE_ID, 0));

        mockMvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(CASE_ID.toString()));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).list(eq(PROJECT_ID), eq(SUITE_ID), eq(null), captor.capture());
        Pageable actual = captor.getValue();
        assertThat(actual.getPageNumber()).isZero();
        assertThat(actual.getPageSize()).isEqualTo(10);
        assertThat(actual.getSort().toList()).containsExactly(
                Sort.Order.asc("position"), Sort.Order.asc("id"));
    }

    @Test
    void listForwardsStatusAndExactExplicitPageableWithoutDefaults() throws Exception {
        when(service.list(eq(PROJECT_ID), eq(SUITE_ID), eq(AutomationTestCaseStatus.INACTIVE),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get(PATH).param("status", "INACTIVE").param("page", "2")
                        .param("size", "7").param("sort", "name,desc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).list(eq(PROJECT_ID), eq(SUITE_ID), eq(AutomationTestCaseStatus.INACTIVE),
                captor.capture());
        Pageable actual = captor.getValue();
        assertThat(actual.getPageNumber()).isEqualTo(2);
        assertThat(actual.getPageSize()).isEqualTo(7);
        assertThat(actual.getSort().toList()).containsExactly(Sort.Order.desc("name"));
        assertThat(actual.getSort().getOrderFor("position")).isNull();
        assertThat(actual.getSort().getOrderFor("id")).isNull();
    }

    @Test
    void listRejectsInvalidStatusWithoutCallingService() throws Exception {
        mockMvc.perform(get(PATH).param("status", "UNKNOWN")).andExpect(status().isBadRequest());
        verifyNoInteractions(service, mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"project missing", "suite missing"})
    void listMapsMissingScopeToStandard404(String message) throws Exception {
        when(service.list(eq(PROJECT_ID), eq(SUITE_ID), eq(null), any(Pageable.class)))
                .thenThrow(new ResourceNotFoundException(message));
        assertApiError(mockMvc.perform(get(PATH)), 404, "Not Found", message, PATH);
    }

    @Test
    void getReturnsMappedResponse() throws Exception {
        AutomationTestCase entity = new AutomationTestCase();
        when(service.get(PROJECT_ID, SUITE_ID, CASE_ID)).thenReturn(entity);
        when(mapper.toResponse(entity)).thenReturn(response(CASE_ID, 0));
        mockMvc.perform(get(casePath())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CASE_ID.toString()));
        verify(service, times(1)).get(PROJECT_ID, SUITE_ID, CASE_ID);
    }

    @ParameterizedTest
    @MethodSource("invalidCasePaths")
    void getRejectsInvalidPathUuid(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isBadRequest());
        verifyNoInteractions(service, mapper);
    }

    @Test
    void getMapsMissingScopeToStandard404() throws Exception {
        when(service.get(PROJECT_ID, SUITE_ID, CASE_ID))
                .thenThrow(new ResourceNotFoundException("case missing"));
        assertApiError(mockMvc.perform(get(casePath())), 404, "Not Found", "case missing", casePath());
    }

    @Test
    void updateIgnoresRawServerFieldsAtServiceEntityBoundary() throws Exception {
        when(mapper.toEntity(any(UpdateAutomationTestCaseRequest.class))).thenAnswer(invocation -> {
            UpdateAutomationTestCaseRequest request = invocation.getArgument(0);
            AutomationTestCase mapped = new AutomationTestCase();
            mapped.setName(request.name());
            mapped.setDescription(request.description());
            mapped.setCaseReference(request.caseReference());
            mapped.setConfiguration(request.configuration());
            return mapped;
        });
        doAnswer(invocation -> invocation.getArgument(3))
                .when(service).update(eq(PROJECT_ID), eq(SUITE_ID), eq(CASE_ID), any());
        when(mapper.toResponse(any())).thenReturn(response(CASE_ID, 0));

        mockMvc.perform(put(casePath()).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Updated","description":"Mutable","caseReference":"updated",
                 "configuration":{"browser":"chromium"},"status":"ARCHIVED","position":99,
                 "id":"%s","automationSuiteId":"%s","version":88,
                 "createdAt":"2026-07-19T10:00:00Z","updatedAt":"2026-07-19T11:00:00Z"}
                """.formatted(CASE_ID, SUITE_ID))).andExpect(status().isOk());

        ArgumentCaptor<AutomationTestCase> captor = ArgumentCaptor.forClass(AutomationTestCase.class);
        verify(service).update(eq(PROJECT_ID), eq(SUITE_ID), eq(CASE_ID), captor.capture());
        AutomationTestCase actual = captor.getValue();
        assertThat(actual.getName()).isEqualTo("Updated");
        assertThat(actual.getDescription()).isEqualTo("Mutable");
        assertThat(actual.getCaseReference()).isEqualTo("updated");
        assertThat(actual.getConfiguration()).containsEntry("browser", "chromium");
        assertThat(actual.getId()).isNull();
        assertThat(actual.getAutomationSuite()).isNull();
        assertThat(actual.getStatus()).isEqualTo(AutomationTestCaseStatus.ACTIVE);
        assertThat(actual.getPosition()).isNull();
        assertThat(actual.getVersion()).isZero();
        assertThat(actual.getCreatedAt()).isNull();
        assertThat(actual.getUpdatedAt()).isNull();
    }

    @ParameterizedTest
    @MethodSource("invalidUpdateBodies")
    void updateRejectsInvalidBodiesBeforeCollaboratorInvocation(String body) throws Exception {
        mockMvc.perform(put(casePath()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"scalar\"", "42", "true"})
    void updateRejectsNonObjectConfigurationBeforeCollaboratorInvocation(String configuration)
            throws Exception {
        mockMvc.perform(put(casePath()).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Case","caseReference":"ref","configuration":%s}
                """.formatted(configuration))).andExpect(status().isBadRequest());
        verifyNoInteractions(service, mapper);
    }

    @ParameterizedTest
    @MethodSource("invalidCasePaths")
    void updateRejectsInvalidPathUuid(String path) throws Exception {
        mockMvc.perform(put(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Case\",\"caseReference\":\"ref\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, mapper);
    }

    @ParameterizedTest
    @MethodSource("updateServiceErrors")
    void updateMapsServiceErrors(RuntimeException exception, int expectedStatus, String error)
            throws Exception {
        AutomationTestCase mapped = new AutomationTestCase();
        when(mapper.toEntity(any(UpdateAutomationTestCaseRequest.class))).thenReturn(mapped);
        when(service.update(PROJECT_ID, SUITE_ID, CASE_ID, mapped)).thenThrow(exception);
        assertApiError(mockMvc.perform(put(casePath()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Case\",\"caseReference\":\"ref\"}")),
                expectedStatus, error, exception.getMessage(), casePath());
    }

    @ParameterizedTest
    @EnumSource(AutomationTestCaseStatus.class)
    void statusEndpointSupportsEveryStatus(AutomationTestCaseStatus requestedStatus) throws Exception {
        AutomationTestCase entity = new AutomationTestCase();
        when(service.updateStatus(PROJECT_ID, SUITE_ID, CASE_ID, requestedStatus)).thenReturn(entity);
        when(mapper.toResponse(entity)).thenReturn(response(CASE_ID, 0));
        mockMvc.perform(patch(statusPath()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + requestedStatus + "\"}"))
                .andExpect(status().isOk());
        verify(service).updateStatus(PROJECT_ID, SUITE_ID, CASE_ID, requestedStatus);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"status\":null}", "{\"status\":\"UNKNOWN\"}"})
    void statusEndpointRejectsMissingNullAndInvalidStatus(String body) throws Exception {
        mockMvc.perform(patch(statusPath()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, mapper);
    }

    @ParameterizedTest
    @MethodSource("invalidStatusPaths")
    void statusEndpointRejectsInvalidPathUuid(String path) throws Exception {
        mockMvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, mapper);
    }

    @ParameterizedTest
    @MethodSource("statusServiceErrors")
    void statusEndpointMapsServiceErrors(RuntimeException exception, int expectedStatus, String error)
            throws Exception {
        when(service.updateStatus(PROJECT_ID, SUITE_ID, CASE_ID, AutomationTestCaseStatus.ACTIVE))
                .thenThrow(exception);
        assertApiError(mockMvc.perform(patch(statusPath()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}")),
                expectedStatus, error, exception.getMessage(), statusPath());
    }

    @Test
    void deleteReturns204WithEmptyBodyAndDelegatesExactlyOnce() throws Exception {
        mockMvc.perform(delete(casePath())).andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(service, times(1)).delete(PROJECT_ID, SUITE_ID, CASE_ID);
    }

    @ParameterizedTest
    @MethodSource("invalidCasePaths")
    void deleteRejectsInvalidPathUuid(String path) throws Exception {
        mockMvc.perform(delete(path)).andExpect(status().isBadRequest());
        verifyNoInteractions(service, mapper);
    }

    @ParameterizedTest
    @MethodSource("deleteServiceErrors")
    void deleteMapsServiceErrors(RuntimeException exception, int expectedStatus, String error)
            throws Exception {
        doThrow(exception).when(service).delete(PROJECT_ID, SUITE_ID, CASE_ID);
        assertApiError(mockMvc.perform(delete(casePath())), expectedStatus, error,
                exception.getMessage(), casePath());
    }

    @Test
    void reorderReturnsCompleteRawArrayInExactServiceOrder() throws Exception {
        AutomationTestCase first = new AutomationTestCase();
        AutomationTestCase second = new AutomationTestCase();
        List<UUID> requestedIds = List.of(SECOND_CASE_ID, CASE_ID);
        when(service.reorder(PROJECT_ID, SUITE_ID, requestedIds)).thenReturn(List.of(second, first));
        when(mapper.toResponse(second)).thenReturn(response(SECOND_CASE_ID, 0));
        when(mapper.toResponse(first)).thenReturn(response(CASE_ID, 1));

        mockMvc.perform(put(PATH + "/order").contentType(MediaType.APPLICATION_JSON).content("""
                {"caseIds":["%s","%s"]}
                """.formatted(SECOND_CASE_ID, CASE_ID)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray()).andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$[0].id").value(SECOND_CASE_ID.toString()))
                .andExpect(jsonPath("$[0].automationSuiteId").value(SUITE_ID.toString()))
                .andExpect(jsonPath("$[0].name").value("Case"))
                .andExpect(jsonPath("$[0].description").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].caseReference").value("case-ref"))
                .andExpect(jsonPath("$[0].configuration").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].position").value(0))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].updatedAt").exists())
                .andExpect(jsonPath("$[1].id").value(CASE_ID.toString()))
                .andExpect(jsonPath("$[1].automationSuiteId").value(SUITE_ID.toString()))
                .andExpect(jsonPath("$[1].name").value("Case"))
                .andExpect(jsonPath("$[1].description").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].caseReference").value("case-ref"))
                .andExpect(jsonPath("$[1].configuration").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].position").value(1))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[1].createdAt").value("2026-07-19T10:00:00Z"))
                .andExpect(jsonPath("$[1].updatedAt").value("2026-07-19T11:00:00Z"))
                .andExpect(jsonPath("$[2]").doesNotExist());

        verify(service, times(1)).reorder(PROJECT_ID, SUITE_ID, requestedIds);
        verify(mapper, times(1)).toResponse(second);
        verify(mapper, times(1)).toResponse(first);
    }

    @Test
    void reorderEmptyListReturnsExactlyEmptyJsonArray() throws Exception {
        when(service.reorder(PROJECT_ID, SUITE_ID, List.of())).thenReturn(List.of());
        mockMvc.perform(put(PATH + "/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseIds\":[]}"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("[]"));
        verify(service, times(1)).reorder(PROJECT_ID, SUITE_ID, List.of());
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"caseIds\":null}", "{\"caseIds\":[null]}",
            "{\"caseIds\":[\"not-a-uuid\"]}"})
    void reorderRejectsInvalidBodyBeforeCollaboratorInvocation(String body) throws Exception {
        mockMvc.perform(put(PATH + "/order").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/projects/not-a-uuid/automation-suites/30000000-0000-0000-0000-000000000001/test-cases/order",
            "/api/v1/projects/20000000-0000-0000-0000-000000000001/automation-suites/not-a-uuid/test-cases/order"
    })
    void reorderRejectsInvalidPathUuid(String path) throws Exception {
        mockMvc.perform(put(path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseIds\":[]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, mapper);
    }

    @ParameterizedTest
    @MethodSource("reorderServiceErrors")
    void reorderMapsServiceFailureWithoutPartialArray(
            RuntimeException exception, int expectedStatus, String error) throws Exception {
        when(service.reorder(PROJECT_ID, SUITE_ID, List.of(CASE_ID))).thenThrow(exception);
        ResultActions result = mockMvc.perform(put(PATH + "/order")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"caseIds\":[\"" + CASE_ID + "\"]}"));
        assertApiError(result, expectedStatus, error, exception.getMessage(), PATH + "/order")
                .andExpect(jsonPath("$").isMap())
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.position").doesNotExist())
                .andExpect(jsonPath("$[0]").doesNotExist());
        verifyNoInteractions(mapper);
    }

    private ResultActions assertApiError(ResultActions result, int statusCode, String error,
            String message, String path) throws Exception {
        return result.andExpect(status().is(statusCode))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(statusCode))
                .andExpect(jsonPath("$.error").value(error))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    private AutomationTestCaseResponse response(UUID id, int position) {
        return new AutomationTestCaseResponse(id, SUITE_ID, "Case", null, "case-ref", null,
                AutomationTestCaseStatus.ACTIVE, position, 1,
                OffsetDateTime.parse("2026-07-19T10:00:00Z"),
                OffsetDateTime.parse("2026-07-19T11:00:00Z"));
    }

    private String casePath() {
        return PATH + "/" + CASE_ID;
    }

    private String statusPath() {
        return casePath() + "/status";
    }

    private static Stream<Arguments> validObjectConfigurations() {
        return Stream.of(
                Arguments.of("{}", false),
                Arguments.of("""
                        {"nested":{"region":"eu"},"array":[1,"two",true,null],
                         "string":"value","number":3,"boolean":false,"nullable":null}
                        """, true));
    }

    private static Map<String, Object> mixedConfiguration() {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("nested", Map.of("region", "eu"));
        List<Object> array = new ArrayList<>();
        array.add(1);
        array.add("two");
        array.add(true);
        array.add(null);
        configuration.put("array", array);
        configuration.put("string", "value");
        configuration.put("number", 3);
        configuration.put("boolean", false);
        configuration.put("nullable", null);
        return configuration;
    }

    @SuppressWarnings("unchecked")
    private static void assertMixedConfiguration(Map<String, Object> configuration) {
        assertThat((Map<String, Object>) configuration.get("nested"))
                .containsEntry("region", "eu");
        assertThat((List<Object>) configuration.get("array"))
                .containsExactly(1, "two", true, null);
        assertThat(configuration).containsEntry("string", "value")
                .containsEntry("number", 3)
                .containsEntry("boolean", false)
                .containsEntry("nullable", null);
    }

    private static Stream<String> invalidCreateBodies() {
        return Stream.of(
                "{\"caseReference\":\"ref\"}",
                "{\"name\":null,\"caseReference\":\"ref\"}",
                "{\"name\":\" \",\"caseReference\":\"ref\"}",
                "{\"name\":\"" + "n".repeat(151) + "\",\"caseReference\":\"ref\"}",
                "{\"name\":\"Case\"}",
                "{\"name\":\"Case\",\"caseReference\":null}",
                "{\"name\":\"Case\",\"caseReference\":\" \"}",
                "{\"name\":\"Case\",\"caseReference\":\"" + "r".repeat(301) + "\"}",
                "{\"name\":\"Case\",\"caseReference\":\"ref\",\"status\":\"UNKNOWN\"}",
                "{\"name\":\"Case\",\"caseReference\":\"ref\"");
    }

    private static Stream<String> invalidUpdateBodies() {
        return Stream.of(
                "{\"caseReference\":\"ref\"}",
                "{\"name\":\" \",\"caseReference\":\"ref\"}",
                "{\"name\":\"" + "n".repeat(151) + "\",\"caseReference\":\"ref\"}",
                "{\"name\":\"Case\"}",
                "{\"name\":\"Case\",\"caseReference\":\" \"}",
                "{\"name\":\"Case\",\"caseReference\":\"" + "r".repeat(301) + "\"}",
                "{\"name\":\"Case\",\"caseReference\":\"ref\"");
    }

    private static Stream<String> invalidCasePaths() {
        return Stream.of(
                "/api/v1/projects/not-a-uuid/automation-suites/" + SUITE_ID + "/test-cases/" + CASE_ID,
                "/api/v1/projects/" + PROJECT_ID + "/automation-suites/not-a-uuid/test-cases/" + CASE_ID,
                PATH + "/not-a-uuid");
    }

    private static Stream<String> invalidStatusPaths() {
        return invalidCasePaths().map(path -> path + "/status");
    }

    private static Stream<Arguments> updateServiceErrors() {
        return Stream.of(
                Arguments.of(new DuplicateResourceException("duplicate"), 409, "Conflict"),
                Arguments.of(new ResourceNotFoundException("missing"), 404, "Not Found"));
    }

    private static Stream<Arguments> statusServiceErrors() {
        return Stream.of(Arguments.of(
                new ResourceNotFoundException("missing"), 404, "Not Found"));
    }

    private static Stream<Arguments> deleteServiceErrors() {
        return Stream.of(Arguments.of(
                new ResourceNotFoundException("missing"), 404, "Not Found"));
    }

    private static Stream<Arguments> reorderServiceErrors() {
        return Stream.of(
                Arguments.of(new InvalidRequestException("invalid membership"), 400, "Bad Request"),
                Arguments.of(new ResourceNotFoundException("missing"), 404, "Not Found"));
    }

    private static Stream<Arguments> createServiceConflicts() {
        return Stream.of(
                Arguments.of(new DuplicateResourceException("duplicate")),
                Arguments.of(new ResourceConflictException("No additional test-case position is available")));
    }
}
