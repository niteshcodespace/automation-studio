package com.automationstudio.api.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.EnvironmentType;
import com.automationstudio.api.entity.Environment;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.exception.DuplicateResourceException;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.repository.EnvironmentRepository;
import com.automationstudio.api.repository.ExecutionRepository;
import com.automationstudio.api.repository.ProjectRepository;
import com.automationstudio.api.service.command.CreateEnvironmentCommand;
import com.automationstudio.api.service.command.UpdateEnvironmentCommand;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class EnvironmentServiceImplTest {

    private static final UUID PROJECT_ID = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");
    private static final UUID ENVIRONMENT_ID = UUID.fromString(
            "50000000-0000-0000-0000-000000000001");
    private static final UUID CURRENT_DEFAULT_ID = UUID.fromString(
            "50000000-0000-0000-0000-000000000002");

    @Mock
    private EnvironmentRepository environmentRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ExecutionRepository executionRepository;

    private EnvironmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EnvironmentServiceImpl(
                environmentRepository,
                projectRepository,
                executionRepository,
                JsonMapper.builder().build());
    }

    @Test
    void createNormalizesDefaultsAndAssignsOnlyRouteProjectOwnership() {
        Project project = new Project();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(environmentRepository.findByProjectIdAndName(PROJECT_ID, "QA"))
                .thenReturn(Optional.empty());
        when(environmentRepository.saveAndFlush(any(Environment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Environment created = service.create(PROJECT_ID, new CreateEnvironmentCommand(
                "  QA  ", "   ", "  https://example.test/path  ", EnvironmentType.QA,
                null, null, null, null));

        assertThat(created.getProject()).isSameAs(project);
        assertThat(created.getName()).isEqualTo("QA");
        assertThat(created.getDescription()).isNull();
        assertThat(created.getBaseUrl()).isEqualTo("https://example.test/path");
        assertThat(created.getType()).isEqualTo(EnvironmentType.QA);
        assertThat(created.getConfiguration()).isEmpty();
        assertThat(created.getSecretReferences()).isEmpty();
        assertThat(created.getStatus()).isEqualTo(EnvironmentStatus.ACTIVE);
        assertThat(created.isDefault()).isFalse();
        verify(projectRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void createDefaultUsesProjectFirstLockAndClearsTheCurrentDefault() {
        Project project = new Project();
        Environment current = persistedEnvironment(CURRENT_DEFAULT_ID, 3);
        current.setDefault(true);
        when(projectRepository.findByIdForUpdate(PROJECT_ID)).thenReturn(Optional.of(project));
        when(environmentRepository.findByProjectIdAndName(PROJECT_ID, "New default"))
                .thenReturn(Optional.empty());
        when(environmentRepository.findByProjectIdAndIsDefaultTrue(PROJECT_ID))
                .thenReturn(Optional.of(current));
        when(environmentRepository.saveAndFlush(any(Environment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Environment created = service.create(PROJECT_ID, new CreateEnvironmentCommand(
                "New default", null, "https://example.test", EnvironmentType.TEST,
                Map.of(), Map.of(), EnvironmentStatus.ACTIVE, true));

        assertThat(current.isDefault()).isFalse();
        assertThat(created.isDefault()).isTrue();
        InOrder order = inOrder(projectRepository, environmentRepository);
        order.verify(projectRepository).findByIdForUpdate(PROJECT_ID);
        order.verify(environmentRepository).findByProjectIdAndName(PROJECT_ID, "New default");
        order.verify(environmentRepository).findByProjectIdAndIsDefaultTrue(PROJECT_ID);
        order.verify(environmentRepository).saveAndFlush(current);
        order.verify(environmentRepository).saveAndFlush(created);
    }

    @Test
    void createRejectsDuplicateAndNonActiveDefault() {
        assertThatExceptionOfType(ResourceConflictException.class)
                .isThrownBy(() -> service.create(PROJECT_ID, new CreateEnvironmentCommand(
                        "Inactive", null, "https://example.test", EnvironmentType.TEST,
                        Map.of(), Map.of(), EnvironmentStatus.INACTIVE, true)));
        verifyNoInteractions(projectRepository, environmentRepository);

        Project project = new Project();
        Environment duplicate = persistedEnvironment(ENVIRONMENT_ID, 0);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(environmentRepository.findByProjectIdAndName(PROJECT_ID, "Duplicate"))
                .thenReturn(Optional.of(duplicate));
        assertThatExceptionOfType(DuplicateResourceException.class)
                .isThrownBy(() -> service.create(PROJECT_ID, command("Duplicate")));
    }

    @Test
    void getAndListAreProjectScopedAndRejectMissingProjects() {
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(false);
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.get(PROJECT_ID, ENVIRONMENT_ID));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.list(
                        PROJECT_ID, null, null, null, PageRequest.of(0, 10)));
        verifyNoInteractions(environmentRepository);

        org.mockito.Mockito.reset(projectRepository, environmentRepository);
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(true);
        when(environmentRepository.findByProjectIdAndId(PROJECT_ID, ENVIRONMENT_ID))
                .thenReturn(Optional.empty());
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.get(PROJECT_ID, ENVIRONMENT_ID));
    }

    @Test
    void listPassesCombinedFiltersThroughOneSpecification() {
        PageRequest pageable = PageRequest.of(1, 5);
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(true);
        when(environmentRepository.findAll(
                any(org.springframework.data.jpa.domain.Specification.class),
                org.mockito.ArgumentMatchers.eq(pageable)))
                .thenReturn(Page.empty(pageable));

        Page<Environment> result = service.list(
                PROJECT_ID, EnvironmentStatus.ACTIVE, EnvironmentType.QA, true, pageable);

        assertThat(result).isEmpty();
        verify(environmentRepository).findAll(
                any(org.springframework.data.jpa.domain.Specification.class),
                org.mockito.ArgumentMatchers.eq(pageable));
    }

    @Test
    void updateReplacesOnlyMutableFieldsAndRequiresTheExpectedVersion() {
        Project owner = new Project();
        Environment existing = persistedEnvironment(ENVIRONMENT_ID, 4);
        existing.setProject(owner);
        existing.setStatus(EnvironmentStatus.INACTIVE);
        existing.setDefault(false);
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(true);
        when(environmentRepository.findByProjectIdAndId(PROJECT_ID, ENVIRONMENT_ID))
                .thenReturn(Optional.of(existing));
        when(environmentRepository.findByProjectIdAndName(PROJECT_ID, "Updated"))
                .thenReturn(Optional.empty());
        when(environmentRepository.saveAndFlush(existing)).thenReturn(existing);

        Environment updated = service.update(
                PROJECT_ID, ENVIRONMENT_ID, 4, updateCommand("  Updated  "));

        assertThat(updated.getName()).isEqualTo("Updated");
        assertThat(updated.getDescription()).isEqualTo("Description");
        assertThat(updated.getBaseUrl()).isEqualTo("https://updated.example.test");
        assertThat(updated.getType()).isEqualTo(EnvironmentType.STAGING);
        assertThat(updated.getStatus()).isEqualTo(EnvironmentStatus.INACTIVE);
        assertThat(updated.isDefault()).isFalse();
        assertThat(updated.getProject()).isSameAs(owner);

        assertThatExceptionOfType(ResourceConflictException.class)
                .isThrownBy(() -> service.update(
                        PROJECT_ID, ENVIRONMENT_ID, 3, updateCommand("Other")));
    }

    @Test
    void updateAllowsCurrentNameButRejectsAnotherEnvironmentName() {
        Environment existing = persistedEnvironment(ENVIRONMENT_ID, 0);
        existing.setName("Current");
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(true);
        when(environmentRepository.findByProjectIdAndId(PROJECT_ID, ENVIRONMENT_ID))
                .thenReturn(Optional.of(existing));
        when(environmentRepository.findByProjectIdAndName(PROJECT_ID, "Current"))
                .thenReturn(Optional.of(existing));
        when(environmentRepository.saveAndFlush(existing)).thenReturn(existing);
        assertThat(service.update(
                PROJECT_ID, ENVIRONMENT_ID, 0, updateCommand("Current"))).isSameAs(existing);

        Environment duplicate = persistedEnvironment(CURRENT_DEFAULT_ID, 0);
        when(environmentRepository.findByProjectIdAndName(PROJECT_ID, "Duplicate"))
                .thenReturn(Optional.of(duplicate));
        assertThatExceptionOfType(DuplicateResourceException.class)
                .isThrownBy(() -> service.update(
                        PROJECT_ID, ENVIRONMENT_ID, 0, updateCommand("Duplicate")));
    }

    @Test
    void optimisticPersistenceFailureIsTranslatedWithoutDatabaseDetails() {
        Environment existing = persistedEnvironment(ENVIRONMENT_ID, 2);
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(true);
        when(environmentRepository.findByProjectIdAndId(PROJECT_ID, ENVIRONMENT_ID))
                .thenReturn(Optional.of(existing));
        when(environmentRepository.findByProjectIdAndName(PROJECT_ID, "Updated"))
                .thenReturn(Optional.empty());
        when(environmentRepository.saveAndFlush(existing))
                .thenThrow(new ObjectOptimisticLockingFailureException(
                        Environment.class, ENVIRONMENT_ID));

        assertThatExceptionOfType(ResourceConflictException.class)
                .isThrownBy(() -> service.update(
                        PROJECT_ID, ENVIRONMENT_ID, 2, updateCommand("Updated")))
                .withMessageNotContaining("SQL");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "relative/path",
            "ftp://example.test",
            "https:///missing-host",
            "https://user@example.test",
            "https://example.test/path#fragment"
    })
    void invalidBaseUrlsAreRejected(String baseUrl) {
        CreateEnvironmentCommand command = new CreateEnvironmentCommand(
                "Target", null, baseUrl, EnvironmentType.TEST,
                Map.of(), Map.of(), null, false);
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.create(PROJECT_ID, command));
        verifyNoInteractions(projectRepository, environmentRepository);
    }

    @Test
    void nameDescriptionAndTypeValidationIsEnforcedBeforePersistence() {
        assertInvalid(command(" "));
        assertInvalid(command("x".repeat(101)));
        assertInvalid(new CreateEnvironmentCommand(
                "Target", "x".repeat(1001), "https://example.test", EnvironmentType.TEST,
                Map.of(), Map.of(), null, false));
        assertInvalid(new CreateEnvironmentCommand(
                "Target", null, "https://example.test", null,
                Map.of(), Map.of(), null, false));
        verifyNoInteractions(projectRepository, environmentRepository);
    }

    @Test
    void recursivelyRejectsProhibitedConfigurationKeysWithoutExposingValues() {
        String rejectedValue = "must-not-appear";
        CreateEnvironmentCommand command = new CreateEnvironmentCommand(
                "Target", null, "https://example.test", EnvironmentType.TEST,
                Map.of("browser", List.of(Map.of("ClientSecret", rejectedValue))),
                Map.of(), null, false);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.create(PROJECT_ID, command))
                .withMessageContaining("configuration.browser[0].ClientSecret")
                .withMessageNotContaining(rejectedValue);
        verifyNoInteractions(projectRepository, environmentRepository);
    }

    @Test
    void validatesJsonSizeAndSecretReferenceShapesWithoutExposingValues() {
        assertInvalid(new CreateEnvironmentCommand(
                "Target", null, "https://example.test", EnvironmentType.TEST,
                Map.of("large", "x".repeat(65_536)), Map.of(), null, false));
        assertInvalid(new CreateEnvironmentCommand(
                "Target", null, "https://example.test", EnvironmentType.TEST,
                Map.of(), Map.of("token", "plain-text-value"), null, false));
        assertInvalid(new CreateEnvironmentCommand(
                "Target", null, "https://example.test", EnvironmentType.TEST,
                Map.of(), Map.of("token", 42), null, false));

        Project project = new Project();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(environmentRepository.findByProjectIdAndName(PROJECT_ID, "Valid"))
                .thenReturn(Optional.empty());
        when(environmentRepository.saveAndFlush(any(Environment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Environment valid = service.create(PROJECT_ID, new CreateEnvironmentCommand(
                "Valid", null, "HTTP://example.test", EnvironmentType.LOCAL,
                null, Map.of("token", "custom-scheme:opaque"), null, false));
        assertThat(valid.getSecretReferences())
                .containsEntry("token", "custom-scheme:opaque");
    }

    @Test
    void statusChangeIsIdempotentAndClearsDefaultWhenLeavingActive() {
        Environment target = persistedEnvironment(ENVIRONMENT_ID, 5);
        target.setStatus(EnvironmentStatus.ACTIVE);
        target.setDefault(true);
        stubLockedTarget(target);
        when(environmentRepository.saveAndFlush(any(Environment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Environment changed = service.changeStatus(
                PROJECT_ID, ENVIRONMENT_ID, 5, EnvironmentStatus.ARCHIVED);
        assertThat(changed.getStatus()).isEqualTo(EnvironmentStatus.ARCHIVED);
        assertThat(changed.isDefault()).isFalse();

        org.mockito.Mockito.reset(environmentRepository);
        target.setStatus(EnvironmentStatus.ARCHIVED);
        stubLockedTarget(target);
        assertThat(service.changeStatus(
                PROJECT_ID, ENVIRONMENT_ID, 5, EnvironmentStatus.ARCHIVED)).isSameAs(target);
        verify(environmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void defaultChangeReplacesClearsAndRejectsNonActiveTargets() {
        Environment current = persistedEnvironment(CURRENT_DEFAULT_ID, 7);
        current.setDefault(true);
        Environment target = persistedEnvironment(ENVIRONMENT_ID, 2);
        target.setStatus(EnvironmentStatus.ACTIVE);
        stubLockedTarget(target);
        when(environmentRepository.findByProjectIdAndIsDefaultTrue(PROJECT_ID))
                .thenReturn(Optional.of(current));
        when(environmentRepository.saveAndFlush(any(Environment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Environment selected = service.changeDefault(
                PROJECT_ID, ENVIRONMENT_ID, 2, true);
        assertThat(current.isDefault()).isFalse();
        assertThat(selected.isDefault()).isTrue();

        target.setDefault(false);
        target.setStatus(EnvironmentStatus.INACTIVE);
        assertThatExceptionOfType(ResourceConflictException.class)
                .isThrownBy(() -> service.changeDefault(
                        PROJECT_ID, ENVIRONMENT_ID, 2, true));
    }

    @Test
    void deleteRejectsReferencesAndTranslatesForeignKeyRace() {
        Environment target = persistedEnvironment(ENVIRONMENT_ID, 1);
        stubLockedTarget(target);
        when(executionRepository.existsByProjectIdAndEnvironmentId(
                PROJECT_ID, ENVIRONMENT_ID)).thenReturn(true);
        assertThatExceptionOfType(ResourceConflictException.class)
                .isThrownBy(() -> service.delete(PROJECT_ID, ENVIRONMENT_ID, 1));
        verify(environmentRepository, never()).delete(any(Environment.class));

        when(executionRepository.existsByProjectIdAndEnvironmentId(
                PROJECT_ID, ENVIRONMENT_ID)).thenReturn(false);
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("fk details"))
                .when(environmentRepository).flush();
        assertThatExceptionOfType(ResourceConflictException.class)
                .isThrownBy(() -> service.delete(PROJECT_ID, ENVIRONMENT_ID, 1))
                .withMessageNotContaining("fk details");
    }

    private void stubLockedTarget(Environment target) {
        when(projectRepository.findByIdForUpdate(PROJECT_ID))
                .thenReturn(Optional.of(new Project()));
        when(environmentRepository.findByProjectIdAndId(PROJECT_ID, ENVIRONMENT_ID))
                .thenReturn(Optional.of(target));
    }

    private void assertInvalid(CreateEnvironmentCommand command) {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.create(PROJECT_ID, command));
    }

    private CreateEnvironmentCommand command(String name) {
        return new CreateEnvironmentCommand(
                name, null, "https://example.test", EnvironmentType.TEST,
                Map.of(), Map.of(), null, false);
    }

    private UpdateEnvironmentCommand updateCommand(String name) {
        return new UpdateEnvironmentCommand(
                name,
                "  Description  ",
                "https://updated.example.test",
                EnvironmentType.STAGING,
                Map.of("browser", "chromium"),
                Map.of("token", "vault://synthetic/token"));
    }

    private Environment persistedEnvironment(UUID id, long version) {
        Environment environment = new Environment();
        ReflectionTestUtils.setField(environment, "id", id);
        ReflectionTestUtils.setField(environment, "version", version);
        environment.setName("Existing");
        environment.setBaseUrl("https://example.test");
        environment.setType(EnvironmentType.TEST);
        return environment;
    }
}
