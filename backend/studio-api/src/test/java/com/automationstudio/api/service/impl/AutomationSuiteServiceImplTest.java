package com.automationstudio.api.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.automationstudio.api.domain.AutomationSuiteStatus;
import com.automationstudio.api.domain.SuiteType;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.Project;
import com.automationstudio.api.exception.DuplicateResourceException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.repository.AutomationSuiteRepository;
import com.automationstudio.api.repository.ProjectRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AutomationSuiteServiceImplTest {

    private static final UUID PROJECT_ID = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_PROJECT_ID = UUID.fromString(
            "20000000-0000-0000-0000-000000000002");
    private static final UUID SUITE_ID = UUID.fromString(
            "30000000-0000-0000-0000-000000000001");

    @Mock
    private AutomationSuiteRepository automationSuiteRepository;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private AutomationSuiteServiceImpl automationSuiteService;

    @Test
    void createsSuiteUnderManagedProjectAndDefaultsNullStatus() {
        Project project = project();
        AutomationSuite suite = suite("  Checkout suite  ");
        suite.setId(SUITE_ID);
        suite.setStatus(null);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(automationSuiteRepository.existsByProjectIdAndName(
                PROJECT_ID, "Checkout suite")).thenReturn(false);
        when(automationSuiteRepository.save(suite)).thenReturn(suite);

        AutomationSuite result = automationSuiteService.create(PROJECT_ID, suite);

        assertThat(result).isSameAs(suite);
        assertThat(suite.getId()).isNull();
        assertThat(suite.getProject()).isSameAs(project);
        assertThat(suite.getName()).isEqualTo("Checkout suite");
        assertThat(suite.getStatus()).isEqualTo(AutomationSuiteStatus.ACTIVE);
        verify(automationSuiteRepository).save(suite);
    }

    @Test
    void creationPreservesSuppliedStatusAndNullableTransitionalFields() {
        AutomationSuite suite = suite("Checkout suite");
        suite.setStatus(AutomationSuiteStatus.ARCHIVED);
        suite.setEngineId(null);
        suite.setSuiteType(null);
        suite.setConfiguration(null);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project()));
        when(automationSuiteRepository.existsByProjectIdAndName(
                PROJECT_ID, "Checkout suite")).thenReturn(false);
        when(automationSuiteRepository.save(suite)).thenReturn(suite);

        AutomationSuite result = automationSuiteService.create(PROJECT_ID, suite);

        assertThat(result.getStatus()).isEqualTo(AutomationSuiteStatus.ARCHIVED);
        assertThat(result.getEngineId()).isNull();
        assertThat(result.getSuiteType()).isNull();
        assertThat(result.getConfiguration()).isNull();
        assertThat(result.getEngineType()).isEqualTo("PLAYWRIGHT");
        assertThat(result.getSuiteReference()).isEqualTo("tests/checkout");
    }

    @Test
    void creationRejectsMissingProjectBeforeSuiteRepositoryUse() {
        AutomationSuite suite = suite("Checkout suite");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> automationSuiteService.create(PROJECT_ID, suite))
                .withMessage("Project not found with id: " + PROJECT_ID);

        verifyNoInteractions(automationSuiteRepository);
    }

    @Test
    void creationRejectsDuplicateName() {
        AutomationSuite suite = suite("  Checkout suite  ");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project()));
        when(automationSuiteRepository.existsByProjectIdAndName(
                PROJECT_ID, "Checkout suite")).thenReturn(true);

        assertDuplicateName(() -> automationSuiteService.create(PROJECT_ID, suite));
        verify(automationSuiteRepository, never()).save(any());
    }

    @Test
    void getReturnsProjectOwnedSuiteUsingScopedLookup() {
        AutomationSuite suite = persistedSuite();
        when(automationSuiteRepository.findByProjectIdAndId(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.of(suite));

        assertThat(automationSuiteService.get(PROJECT_ID, SUITE_ID)).isSameAs(suite);
        verify(automationSuiteRepository).findByProjectIdAndId(PROJECT_ID, SUITE_ID);
        verify(automationSuiteRepository, never()).findById(SUITE_ID);
    }

    @Test
    void getRejectsMissingOrCrossProjectSuite() {
        when(automationSuiteRepository.findByProjectIdAndId(OTHER_PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.empty());

        assertMissingSuite(() -> automationSuiteService.get(OTHER_PROJECT_ID, SUITE_ID),
                OTHER_PROJECT_ID);
        verify(automationSuiteRepository).findByProjectIdAndId(OTHER_PROJECT_ID, SUITE_ID);
    }

    @Test
    void listWithoutStatusUsesUnfilteredMethodAndPreservesPageable() {
        Pageable pageable = PageRequest.of(2, 25);
        Page<AutomationSuite> expected = new PageImpl<>(java.util.List.of(persistedSuite()));
        when(automationSuiteRepository.findByProjectId(PROJECT_ID, pageable))
                .thenReturn(expected);

        assertThat(automationSuiteService.list(PROJECT_ID, null, pageable)).isSameAs(expected);
        verify(automationSuiteRepository).findByProjectId(PROJECT_ID, pageable);
        verify(automationSuiteRepository, never())
                .findByProjectIdAndStatus(any(), any(), any());
    }

    @Test
    void listWithStatusUsesFilteredMethodAndPreservesPageable() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<AutomationSuite> expected = Page.empty(pageable);
        when(automationSuiteRepository.findByProjectIdAndStatus(
                PROJECT_ID, AutomationSuiteStatus.ARCHIVED, pageable)).thenReturn(expected);

        assertThat(automationSuiteService.list(
                PROJECT_ID, AutomationSuiteStatus.ARCHIVED, pageable)).isSameAs(expected);
        verify(automationSuiteRepository).findByProjectIdAndStatus(
                PROJECT_ID, AutomationSuiteStatus.ARCHIVED, pageable);
        verify(automationSuiteRepository, never()).findByProjectId(any(), any(Pageable.class));
    }

    @Test
    void updateCopiesOnlyMutableFieldsAndPreservesAggregateMetadata() {
        AutomationSuite existing = persistedSuite();
        AutomationSuite updates = suite("  Updated suite  ");
        updates.setDescription("Updated description");
        updates.setEngineType("SELENIUM");
        updates.setSuiteReference("tests/updated");
        updates.setEngineId("selenium-java");
        updates.setSuiteType(SuiteType.UI);
        updates.setConfiguration(Map.of("browser", "firefox"));
        updates.setId(UUID.randomUUID());
        updates.setProject(new Project());
        updates.setStatus(AutomationSuiteStatus.ARCHIVED);
        updates.setVersion(99);
        updates.setCreatedAt(OffsetDateTime.parse("2020-01-01T00:00:00Z"));
        updates.setUpdatedAt(OffsetDateTime.parse("2020-01-02T00:00:00Z"));
        UUID originalId = existing.getId();
        Project originalProject = existing.getProject();
        AutomationSuiteStatus originalStatus = existing.getStatus();
        long originalVersion = existing.getVersion();
        OffsetDateTime originalCreatedAt = existing.getCreatedAt();
        OffsetDateTime originalUpdatedAt = existing.getUpdatedAt();
        when(automationSuiteRepository.findByProjectIdAndId(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.of(existing));
        when(automationSuiteRepository.existsByProjectIdAndName(
                PROJECT_ID, "Updated suite")).thenReturn(false);
        when(automationSuiteRepository.save(existing)).thenReturn(existing);

        AutomationSuite result = automationSuiteService.update(PROJECT_ID, SUITE_ID, updates);

        assertThat(result.getName()).isEqualTo("Updated suite");
        assertThat(result.getDescription()).isEqualTo("Updated description");
        assertThat(result.getEngineType()).isEqualTo("SELENIUM");
        assertThat(result.getSuiteReference()).isEqualTo("tests/updated");
        assertThat(result.getEngineId()).isEqualTo("selenium-java");
        assertThat(result.getSuiteType()).isEqualTo(SuiteType.UI);
        assertThat(result.getConfiguration()).containsEntry("browser", "firefox");
        assertThat(result.getId()).isEqualTo(originalId);
        assertThat(result.getProject()).isSameAs(originalProject);
        assertThat(result.getStatus()).isEqualTo(originalStatus);
        assertThat(result.getVersion()).isEqualTo(originalVersion);
        assertThat(result.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(result.getUpdatedAt()).isEqualTo(originalUpdatedAt);
    }

    @Test
    void updateAllowsUnchangedNameWithoutDuplicateCheckAndClearsNullableFields() {
        AutomationSuite existing = persistedSuite();
        existing.setEngineId("playwright-java");
        existing.setSuiteType(SuiteType.UI);
        existing.setConfiguration(Map.of("browser", "chromium"));
        AutomationSuite updates = suite("  Checkout suite  ");
        updates.setEngineId(null);
        updates.setSuiteType(null);
        updates.setConfiguration(null);
        when(automationSuiteRepository.findByProjectIdAndId(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.of(existing));
        when(automationSuiteRepository.save(existing)).thenReturn(existing);

        AutomationSuite result = automationSuiteService.update(PROJECT_ID, SUITE_ID, updates);

        assertThat(result.getEngineId()).isNull();
        assertThat(result.getSuiteType()).isNull();
        assertThat(result.getConfiguration()).isNull();
        verify(automationSuiteRepository, never()).existsByProjectIdAndName(any(), any());
    }

    @Test
    void updateRejectsConflictingRename() {
        AutomationSuite existing = persistedSuite();
        AutomationSuite updates = suite("Conflicting suite");
        when(automationSuiteRepository.findByProjectIdAndId(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.of(existing));
        when(automationSuiteRepository.existsByProjectIdAndName(
                PROJECT_ID, "Conflicting suite")).thenReturn(true);

        assertThatExceptionOfType(DuplicateResourceException.class)
                .isThrownBy(() -> automationSuiteService.update(PROJECT_ID, SUITE_ID, updates));
        verify(automationSuiteRepository, never()).save(any());
    }

    @Test
    void updateRejectsMissingSuite() {
        when(automationSuiteRepository.findByProjectIdAndId(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.empty());

        assertMissingSuite(() -> automationSuiteService.update(
                PROJECT_ID, SUITE_ID, suite("Updated suite")), PROJECT_ID);
        verify(automationSuiteRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(AutomationSuiteStatus.class)
    void changeStatusSupportsEveryExistingStatus(AutomationSuiteStatus status) {
        AutomationSuite suite = persistedSuite();
        when(automationSuiteRepository.findByProjectIdAndId(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.of(suite));
        when(automationSuiteRepository.save(suite)).thenReturn(suite);

        assertThat(automationSuiteService.changeStatus(PROJECT_ID, SUITE_ID, status)
                .getStatus()).isEqualTo(status);
        verify(automationSuiteRepository).save(suite);
    }

    @Test
    void changeStatusRejectsNullBeforeRepositoryUse() {
        assertThatNullPointerException()
                .isThrownBy(() -> automationSuiteService.changeStatus(
                        PROJECT_ID, SUITE_ID, null))
                .withMessage("Automation suite status must not be null");
        verifyNoInteractions(automationSuiteRepository);
    }

    @Test
    void changeStatusRejectsMissingSuite() {
        when(automationSuiteRepository.findByProjectIdAndId(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.empty());

        assertMissingSuite(() -> automationSuiteService.changeStatus(
                PROJECT_ID, SUITE_ID, AutomationSuiteStatus.ACTIVE), PROJECT_ID);
        verify(automationSuiteRepository, never()).save(any());
    }

    @Test
    void deleteRemovesProjectOwnedSuite() {
        AutomationSuite suite = persistedSuite();
        when(automationSuiteRepository.findByProjectIdAndId(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.of(suite));

        automationSuiteService.delete(PROJECT_ID, SUITE_ID);

        verify(automationSuiteRepository).delete(suite);
    }

    @Test
    void deleteRejectsMissingSuiteWithoutCallingDelete() {
        when(automationSuiteRepository.findByProjectIdAndId(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.empty());

        assertMissingSuite(() -> automationSuiteService.delete(PROJECT_ID, SUITE_ID), PROJECT_ID);
        verify(automationSuiteRepository, never()).delete(any());
    }

    private Project project() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        return project;
    }

    private AutomationSuite suite(String name) {
        AutomationSuite suite = new AutomationSuite();
        suite.setName(name);
        suite.setEngineType("PLAYWRIGHT");
        suite.setSuiteReference("tests/checkout");
        return suite;
    }

    private AutomationSuite persistedSuite() {
        AutomationSuite suite = suite("Checkout suite");
        suite.setId(SUITE_ID);
        suite.setProject(project());
        suite.setVersion(4);
        suite.setCreatedAt(OffsetDateTime.parse("2026-07-17T10:00:00Z"));
        suite.setUpdatedAt(OffsetDateTime.parse("2026-07-17T11:00:00Z"));
        return suite;
    }

    private void assertDuplicateName(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatExceptionOfType(DuplicateResourceException.class)
                .isThrownBy(call)
                .withMessage("Automation suite with name 'Checkout suite' already exists in project: "
                        + PROJECT_ID);
    }

    private void assertMissingSuite(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, UUID projectId) {
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(call)
                .withMessage("Automation suite not found with id: " + SUITE_ID
                        + " in project: " + projectId);
    }
}
