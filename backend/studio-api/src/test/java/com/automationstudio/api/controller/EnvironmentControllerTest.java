package com.automationstudio.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.EnvironmentType;
import com.automationstudio.api.dto.environment.CreateEnvironmentRequest;
import com.automationstudio.api.dto.environment.EnvironmentResponse;
import com.automationstudio.api.dto.environment.UpdateEnvironmentRequest;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.exception.GlobalExceptionHandler;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.mapper.EnvironmentMapper;
import com.automationstudio.api.service.EnvironmentService;
import com.automationstudio.api.service.command.CreateEnvironmentCommand;
import com.automationstudio.api.service.command.UpdateEnvironmentCommand;
import java.lang.reflect.Field;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(EnvironmentController.class)
@Import(GlobalExceptionHandler.class)
class EnvironmentControllerTest {

    private static final UUID PROJECT_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ENVIRONMENT_ID =
            UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final String BASE_PATH =
            "/api/v1/projects/" + PROJECT_ID + "/environments";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EnvironmentService service;

    @MockitoBean
    private EnvironmentMapper mapper;

    @Test
    void createReturns201LocationAndResponse() throws Exception {
        CreateEnvironmentRequest request = createRequest();
        CreateEnvironmentCommand command = new CreateEnvironmentCommand(
                request.name(), request.description(), request.baseUrl(), request.type(),
                request.configuration(), request.secretReferences(), request.status(),
                request.isDefault());
        Environment saved = environmentWithId();
        when(mapper.toCommand(request)).thenReturn(command);
        when(service.create(PROJECT_ID, command)).thenReturn(saved);
        when(mapper.toResponse(saved)).thenReturn(response(1));

        mockMvc.perform(post(BASE_PATH).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location", "http://localhost" + BASE_PATH + "/" + ENVIRONMENT_ID))
                .andExpect(jsonPath("$.id").value(ENVIRONMENT_ID.toString()))
                .andExpect(jsonPath("$.secretReferences.token").value("vault://qa/token"));
    }

    @Test
    void listPassesCombinedFiltersAndDefaultSort() throws Exception {
        Environment environment = environmentWithId();
        when(service.list(eq(PROJECT_ID), eq(EnvironmentStatus.ACTIVE),
                eq(EnvironmentType.QA), eq(true), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(environment)));
        when(mapper.toResponse(environment)).thenReturn(response(1));

        mockMvc.perform(get(BASE_PATH).param("status", "ACTIVE")
                        .param("type", "QA").param("isDefault", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("QA"));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).list(eq(PROJECT_ID), eq(EnvironmentStatus.ACTIVE),
                eq(EnvironmentType.QA), eq(true), captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(
                Sort.by("name").ascending().and(Sort.by("id").ascending()));
    }

    @Test
    void listPreservesExplicitPagingAndSorting() throws Exception {
        when(service.list(eq(PROJECT_ID), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));

        mockMvc.perform(get(BASE_PATH).param("page", "2").param("size", "5")
                        .param("sort", "name,desc"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).list(eq(PROJECT_ID), eq(null), eq(null), eq(null), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
        assertThat(captor.getValue().getSort().getOrderFor("name").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void getReturnsProjectScopedResponse() throws Exception {
        Environment environment = environmentWithId();
        when(service.get(PROJECT_ID, ENVIRONMENT_ID)).thenReturn(environment);
        when(mapper.toResponse(environment)).thenReturn(response(1));

        mockMvc.perform(get(environmentPath())).andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(PROJECT_ID.toString()));
    }

    @Test
    void updateParsesIfMatchAndReturnsNewVersion() throws Exception {
        UpdateEnvironmentRequest request = updateRequest();
        UpdateEnvironmentCommand command = new UpdateEnvironmentCommand(
                request.name(), request.description(), request.baseUrl(), request.type(),
                request.configuration(), request.secretReferences());
        Environment saved = environmentWithId();
        when(mapper.toCommand(request)).thenReturn(command);
        when(service.update(PROJECT_ID, ENVIRONMENT_ID, 3, command)).thenReturn(saved);
        when(mapper.toResponse(saved)).thenReturn(response(4));

        mockMvc.perform(put(environmentPath()).header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(4));
        verify(service).update(PROJECT_ID, ENVIRONMENT_ID, 3, command);
    }

    @Test
    void statusAndDefaultPatchesRequireAndPassExpectedVersion() throws Exception {
        Environment saved = environmentWithId();
        when(service.changeStatus(PROJECT_ID, ENVIRONMENT_ID, 7, EnvironmentStatus.ARCHIVED))
                .thenReturn(saved);
        when(service.changeDefault(PROJECT_ID, ENVIRONMENT_ID, 8, false)).thenReturn(saved);
        when(mapper.toResponse(saved)).thenReturn(response(9));

        mockMvc.perform(patch(environmentPath() + "/status").header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch(environmentPath() + "/default").header("If-Match", "\"8\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isDefault\":false}"))
                .andExpect(status().isOk());

        verify(service).changeStatus(PROJECT_ID, ENVIRONMENT_ID, 7, EnvironmentStatus.ARCHIVED);
        verify(service).changeDefault(PROJECT_ID, ENVIRONMENT_ID, 8, false);
    }

    @Test
    void deleteParsesIfMatchAndReturns204() throws Exception {
        mockMvc.perform(delete(environmentPath()).header("If-Match", "\"0\""))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).delete(PROJECT_ID, ENVIRONMENT_ID, 0);
    }

    @Test
    void mutationWithoutIfMatchReturnsSafe400WithoutServiceCall() throws Exception {
        mockMvc.perform(delete(environmentPath()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("If-Match header is required"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "W/\"3\"", "*", "\"-1\"", "\"3\",\"4\"", "\"abc\"",
            "\"01\"", "\"9223372036854775808\""})
    void malformedIfMatchReturns400(String value) throws Exception {
        mockMvc.perform(delete(environmentPath()).header("If-Match", value))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"scalar\"", "42", "true"})
    void createRejectsNonObjectJsonRoots(String value) throws Exception {
        String body = """
                {"name":"QA","baseUrl":"https://qa.example.test","type":"QA",
                 "configuration":%s,"secretReferences":{}}
                """.formatted(value);
        mockMvc.perform(post(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"scalar\"", "42", "false"})
    void createRejectsNonObjectSecretReferenceRoots(String value) throws Exception {
        String body = """
                {"name":"QA","baseUrl":"https://qa.example.test","type":"QA",
                 "configuration":{},"secretReferences":%s}
                """.formatted(value);
        mockMvc.perform(post(BASE_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, mapper);
    }

    @Test
    void malformedJsonAndInvalidEnumsReturn400() throws Exception {
        mockMvc.perform(post(BASE_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"QA\""))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE_PATH).param("type", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, mapper);
    }

    @Test
    void missingRequiredFieldsAndPatchValuesReturn400() throws Exception {
        mockMvc.perform(post(BASE_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"baseUrl\":\" \",\"type\":null}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(environmentPath() + "/status").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":null}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(environmentPath() + "/default").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, mapper);
    }

    @Test
    void staleVersionConflictUsesExistingApiErrorShape() throws Exception {
        UpdateEnvironmentRequest request = updateRequest();
        UpdateEnvironmentCommand command = new UpdateEnvironmentCommand(
                request.name(), request.description(), request.baseUrl(), request.type(),
                request.configuration(), request.secretReferences());
        when(mapper.toCommand(request)).thenReturn(command);
        when(service.update(PROJECT_ID, ENVIRONMENT_ID, 2, command))
                .thenThrow(new ResourceConflictException("Environment version conflict"));

        mockMvc.perform(put(environmentPath()).header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Environment version conflict"));
    }

    @Test
    void updateRequestDoesNotExposeServerControlledFields() throws Exception {
        String json = objectMapper.writeValueAsString(updateRequest());
        assertThat(json).doesNotContain("id", "projectId", "status", "isDefault",
                "version", "createdAt", "updatedAt");
    }

    private CreateEnvironmentRequest createRequest() {
        return new CreateEnvironmentRequest(
                "QA", "QA target", "https://qa.example.test", EnvironmentType.QA,
                Map.of("region", "eu-west-1"), Map.of("token", "vault://qa/token"),
                EnvironmentStatus.ACTIVE, true);
    }

    private UpdateEnvironmentRequest updateRequest() {
        return new UpdateEnvironmentRequest(
                "QA", "Updated QA target", "https://qa.example.test", EnvironmentType.QA,
                Map.of("region", "eu-west-1"), Map.of("token", "vault://qa/token"));
    }

    private EnvironmentResponse response(long version) {
        return new EnvironmentResponse(
                ENVIRONMENT_ID, PROJECT_ID, "QA", "QA target", "https://qa.example.test",
                EnvironmentType.QA, Map.of("region", "eu-west-1"),
                Map.of("token", "vault://qa/token"), EnvironmentStatus.ACTIVE, true, version,
                OffsetDateTime.parse("2026-07-24T10:00:00Z"),
                OffsetDateTime.parse("2026-07-24T11:00:00Z"));
    }

    private Environment environmentWithId() {
        Environment environment = new Environment();
        try {
            Field field = Environment.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(environment, ENVIRONMENT_ID);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
        return environment;
    }

    private String environmentPath() {
        return BASE_PATH + "/" + ENVIRONMENT_ID;
    }
}
