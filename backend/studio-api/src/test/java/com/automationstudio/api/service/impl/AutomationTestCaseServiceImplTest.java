package com.automationstudio.api.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.automationstudio.api.domain.AutomationTestCaseStatus;
import com.automationstudio.api.entity.AutomationSuite;
import com.automationstudio.api.entity.AutomationTestCase;
import com.automationstudio.api.exception.DuplicateResourceException;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.exception.ResourceConflictException;
import com.automationstudio.api.exception.ResourceNotFoundException;
import com.automationstudio.api.repository.AutomationSuiteRepository;
import com.automationstudio.api.repository.AutomationTestCaseRepository;
import com.automationstudio.api.repository.ProjectRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AutomationTestCaseServiceImplTest {

    private static final UUID PROJECT_ID = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");
    private static final UUID SUITE_ID = UUID.fromString(
            "30000000-0000-0000-0000-000000000001");
    private static final UUID CASE_ID = UUID.fromString(
            "40000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_CASE_ID = UUID.fromString(
            "40000000-0000-0000-0000-000000000002");

    @Mock
    private AutomationTestCaseRepository testCaseRepository;

    @Mock
    private AutomationSuiteRepository automationSuiteRepository;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private AutomationTestCaseServiceImpl service;

    @Test
    void createNormalizesAndForcesServerFieldsAtPositionZeroInLockOrder() {
        AutomationTestCase input = testCase("  Checkout case  ", "  checkout succeeds  ", 99);
        input.setId(CASE_ID);
        input.setVersion(42);
        input.setCreatedAt(OffsetDateTime.parse("2020-01-01T00:00:00Z"));
        input.setUpdatedAt(OffsetDateTime.parse("2020-01-02T00:00:00Z"));
        input.setStatus(null);
        stubLockedSuite();
        when(testCaseRepository.findMaximumPositionByAutomationSuiteId(SUITE_ID))
                .thenReturn(Optional.empty());
        when(testCaseRepository.save(input)).thenReturn(input);

        AutomationTestCase result = service.create(PROJECT_ID, SUITE_ID, input);

        assertThat(result.getId()).isNull();
        assertThat(result.getAutomationSuite().getId()).isEqualTo(SUITE_ID);
        assertThat(result.getName()).isEqualTo("Checkout case");
        assertThat(result.getCaseReference()).isEqualTo("checkout succeeds");
        assertThat(result.getPosition()).isZero();
        assertThat(result.getStatus()).isEqualTo(AutomationTestCaseStatus.ACTIVE);
        assertThat(result.getVersion()).isZero();
        assertThat(result.getCreatedAt()).isNull();
        assertThat(result.getUpdatedAt()).isNull();
        InOrder order = inOrder(projectRepository, automationSuiteRepository, testCaseRepository);
        order.verify(projectRepository).existsById(PROJECT_ID);
        order.verify(automationSuiteRepository)
                .findByProjectIdAndIdForUpdate(PROJECT_ID, SUITE_ID);
        order.verify(testCaseRepository).existsByAutomationSuiteIdAndName(
                SUITE_ID, "Checkout case");
        order.verify(testCaseRepository).existsByAutomationSuiteIdAndCaseReference(
                SUITE_ID, "checkout succeeds");
        order.verify(testCaseRepository).findMaximumPositionByAutomationSuiteId(SUITE_ID);
        order.verify(testCaseRepository).save(input);
    }

    @Test
    void createAppendsAndPreservesNullableFieldsAndSuppliedStatus() {
        AutomationTestCase input = testCase("Case", "case-ref", 0);
        input.setDescription(null);
        input.setConfiguration(null);
        input.setStatus(AutomationTestCaseStatus.ARCHIVED);
        stubLockedSuite();
        when(testCaseRepository.findMaximumPositionByAutomationSuiteId(SUITE_ID))
                .thenReturn(Optional.of(7));
        when(testCaseRepository.save(input)).thenReturn(input);

        AutomationTestCase result = service.create(PROJECT_ID, SUITE_ID, input);

        assertThat(result.getPosition()).isEqualTo(8);
        assertThat(result.getStatus()).isEqualTo(AutomationTestCaseStatus.ARCHIVED);
        assertThat(result.getDescription()).isNull();
        assertThat(result.getConfiguration()).isNull();
    }

    @Test
    void createRejectsPositionOverflowWithoutSaving() {
        AutomationTestCase input = testCase("Case", "case-ref", 0);
        stubLockedSuite();
        when(testCaseRepository.findMaximumPositionByAutomationSuiteId(SUITE_ID))
                .thenReturn(Optional.of(Integer.MAX_VALUE));

        assertThatExceptionOfType(ResourceConflictException.class)
                .isThrownBy(() -> service.create(PROJECT_ID, SUITE_ID, input));
        verify(testCaseRepository, never()).save(any());
    }

    @Test
    void createRejectsDuplicatesAndMissingOwnership() {
        AutomationTestCase input = testCase("Case", "case-ref", 0);
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(false);
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.create(PROJECT_ID, SUITE_ID, input));
        verifyNoInteractions(automationSuiteRepository, testCaseRepository);

        org.mockito.Mockito.reset(projectRepository, automationSuiteRepository, testCaseRepository);
        stubLockedSuite();
        when(testCaseRepository.existsByAutomationSuiteIdAndName(SUITE_ID, "Case"))
                .thenReturn(true);
        assertThatExceptionOfType(DuplicateResourceException.class)
                .isThrownBy(() -> service.create(PROJECT_ID, SUITE_ID, input));
        verify(testCaseRepository, never()).save(any());
    }

    @Test
    void createRejectsDuplicateReferenceAndBlankNormalizedValues() {
        AutomationTestCase input = testCase("Case", "case-ref", 0);
        stubLockedSuite();
        when(testCaseRepository.existsByAutomationSuiteIdAndCaseReference(
                SUITE_ID, "case-ref")).thenReturn(true);
        assertThatExceptionOfType(DuplicateResourceException.class)
                .isThrownBy(() -> service.create(PROJECT_ID, SUITE_ID, input));

        org.mockito.Mockito.reset(projectRepository, automationSuiteRepository, testCaseRepository);
        AutomationTestCase blank = testCase("   ", "case-ref", 0);
        stubLockedSuite();
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.create(PROJECT_ID, SUITE_ID, blank));
        verify(testCaseRepository, never()).save(any());
    }

    @Test
    void createRejectsMissingOrCrossProjectSuiteBeforeCaseQueries() {
        AutomationTestCase input = testCase("Case", "case-ref", 0);
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(true);
        when(automationSuiteRepository.findByProjectIdAndIdForUpdate(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.create(PROJECT_ID, SUITE_ID, input));

        verifyNoInteractions(testCaseRepository);
    }

    @Test
    void getAndListVerifyCompleteOwnershipAndPreservePageable() {
        AutomationTestCase existing = persistedCase(CASE_ID, 0);
        Pageable pageable = PageRequest.of(2, 5);
        Page<AutomationTestCase> page = new PageImpl<>(List.of(existing), pageable, 1);
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(true);
        when(automationSuiteRepository.findByProjectIdAndId(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.of(suite()));
        when(testCaseRepository.findByAutomationSuiteIdAndId(SUITE_ID, CASE_ID))
                .thenReturn(Optional.of(existing));
        when(testCaseRepository.findByAutomationSuiteId(SUITE_ID, pageable)).thenReturn(page);

        assertThat(service.get(PROJECT_ID, SUITE_ID, CASE_ID)).isSameAs(existing);
        assertThat(service.list(PROJECT_ID, SUITE_ID, null, pageable)).isSameAs(page);
        verify(testCaseRepository).findByAutomationSuiteId(SUITE_ID, pageable);
    }

    @Test
    void filteredListUsesStatusQueryAndCrossSuiteGetIsNotFound() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<AutomationTestCase> page = Page.empty(pageable);
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(true);
        when(automationSuiteRepository.findByProjectIdAndId(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.of(suite()));
        when(testCaseRepository.findByAutomationSuiteIdAndStatus(
                SUITE_ID, AutomationTestCaseStatus.ARCHIVED, pageable)).thenReturn(page);
        when(testCaseRepository.findByAutomationSuiteIdAndId(SUITE_ID, CASE_ID))
                .thenReturn(Optional.empty());

        assertThat(service.list(PROJECT_ID, SUITE_ID,
                AutomationTestCaseStatus.ARCHIVED, pageable)).isSameAs(page);
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.get(PROJECT_ID, SUITE_ID, CASE_ID));
    }

    @Test
    void listRejectsMissingProjectBeforeSuiteOrCaseQueries() {
        Pageable pageable = PageRequest.of(0, 10);
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(false);

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.list(PROJECT_ID, SUITE_ID, null, pageable));

        verifyNoInteractions(automationSuiteRepository, testCaseRepository);
    }

    @Test
    void listRejectsMissingOrCrossProjectSuiteBeforeCaseQuery() {
        Pageable pageable = PageRequest.of(0, 10);
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(true);
        when(automationSuiteRepository.findByProjectIdAndId(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.list(PROJECT_ID, SUITE_ID, null, pageable));

        verifyNoInteractions(testCaseRepository);
    }

    @Test
    void updateCopiesOnlyMutableFieldsAndUsesExcludingDuplicateChecks() {
        AutomationTestCase existing = persistedCase(CASE_ID, 4);
        existing.setStatus(AutomationTestCaseStatus.INACTIVE);
        AutomationTestCase updates = testCase("  Updated  ", "  updated-ref  ", 99);
        updates.setDescription("description");
        updates.setConfiguration(Map.of("browser", "firefox"));
        updates.setStatus(AutomationTestCaseStatus.ARCHIVED);
        stubLockedSuite();
        when(testCaseRepository.findByAutomationSuiteIdAndIdForUpdate(SUITE_ID, CASE_ID))
                .thenReturn(Optional.of(existing));
        when(testCaseRepository.save(existing)).thenReturn(existing);

        AutomationTestCase result = service.update(PROJECT_ID, SUITE_ID, CASE_ID, updates);

        assertThat(result.getName()).isEqualTo("Updated");
        assertThat(result.getCaseReference()).isEqualTo("updated-ref");
        assertThat(result.getConfiguration()).containsEntry("browser", "firefox");
        assertThat(result.getStatus()).isEqualTo(AutomationTestCaseStatus.INACTIVE);
        assertThat(result.getPosition()).isEqualTo(4);
        verify(testCaseRepository).existsByAutomationSuiteIdAndNameAndIdNot(
                SUITE_ID, "Updated", CASE_ID);
        verify(testCaseRepository).existsByAutomationSuiteIdAndCaseReferenceAndIdNot(
                SUITE_ID, "updated-ref", CASE_ID);
    }

    @Test
    void updateRejectsMissingProjectOrSuiteBeforeCaseQueries() {
        AutomationTestCase updates = testCase("Updated", "updated-ref", 0);
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(false);
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.update(PROJECT_ID, SUITE_ID, CASE_ID, updates));
        verifyNoInteractions(automationSuiteRepository, testCaseRepository);

        org.mockito.Mockito.reset(projectRepository, automationSuiteRepository, testCaseRepository);
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(true);
        when(automationSuiteRepository.findByProjectIdAndIdForUpdate(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.empty());
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.update(PROJECT_ID, SUITE_ID, CASE_ID, updates));
        verifyNoInteractions(testCaseRepository);
    }

    @Test
    void updateRejectsMissingOrCrossSuiteCaseBeforeDuplicateQueries() {
        AutomationTestCase updates = testCase("Updated", "updated-ref", 0);
        stubLockedSuite();
        when(testCaseRepository.findByAutomationSuiteIdAndIdForUpdate(SUITE_ID, CASE_ID))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.update(PROJECT_ID, SUITE_ID, CASE_ID, updates));

        verify(testCaseRepository, never())
                .existsByAutomationSuiteIdAndNameAndIdNot(any(), any(), any());
        verify(testCaseRepository, never()).save(any());
    }

    @Test
    void updateRejectsDuplicateNameBeforeReferenceCheckAndSave() {
        AutomationTestCase existing = persistedCase(CASE_ID, 0);
        AutomationTestCase updates = testCase("Updated", "updated-ref", 0);
        stubLockedSuite();
        when(testCaseRepository.findByAutomationSuiteIdAndIdForUpdate(SUITE_ID, CASE_ID))
                .thenReturn(Optional.of(existing));
        when(testCaseRepository.existsByAutomationSuiteIdAndNameAndIdNot(
                SUITE_ID, "Updated", CASE_ID)).thenReturn(true);

        assertThatExceptionOfType(DuplicateResourceException.class)
                .isThrownBy(() -> service.update(PROJECT_ID, SUITE_ID, CASE_ID, updates));

        verify(testCaseRepository, never())
                .existsByAutomationSuiteIdAndCaseReferenceAndIdNot(any(), any(), any());
        verify(testCaseRepository, never()).save(any());
    }

    @Test
    void updateRejectsDuplicateReferenceAfterNameCheckWithoutSaving() {
        AutomationTestCase existing = persistedCase(CASE_ID, 0);
        AutomationTestCase updates = testCase("Updated", "updated-ref", 0);
        stubLockedSuite();
        when(testCaseRepository.findByAutomationSuiteIdAndIdForUpdate(SUITE_ID, CASE_ID))
                .thenReturn(Optional.of(existing));
        when(testCaseRepository.existsByAutomationSuiteIdAndCaseReferenceAndIdNot(
                SUITE_ID, "updated-ref", CASE_ID)).thenReturn(true);

        assertThatExceptionOfType(DuplicateResourceException.class)
                .isThrownBy(() -> service.update(PROJECT_ID, SUITE_ID, CASE_ID, updates));

        InOrder order = inOrder(testCaseRepository);
        order.verify(testCaseRepository).existsByAutomationSuiteIdAndNameAndIdNot(
                SUITE_ID, "Updated", CASE_ID);
        order.verify(testCaseRepository).existsByAutomationSuiteIdAndCaseReferenceAndIdNot(
                SUITE_ID, "updated-ref", CASE_ID);
        verify(testCaseRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(AutomationTestCaseStatus.class)
    void statusUpdateSupportsAllStates(AutomationTestCaseStatus status) {
        AutomationTestCase existing = persistedCase(CASE_ID, 0);
        stubLockedSuite();
        when(testCaseRepository.findByAutomationSuiteIdAndIdForUpdate(SUITE_ID, CASE_ID))
                .thenReturn(Optional.of(existing));
        when(testCaseRepository.save(existing)).thenReturn(existing);

        assertThat(service.updateStatus(PROJECT_ID, SUITE_ID, CASE_ID, status).getStatus())
                .isEqualTo(status);
    }

    @Test
    void nullStatusIsRejectedAfterValidHierarchyWithoutMutationOrSave() {
        AutomationTestCase existing = persistedCase(CASE_ID, 0);
        AutomationTestCaseStatus originalStatus = existing.getStatus();
        stubLockedSuite();
        when(testCaseRepository.findByAutomationSuiteIdAndIdForUpdate(SUITE_ID, CASE_ID))
                .thenReturn(Optional.of(existing));

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.updateStatus(PROJECT_ID, SUITE_ID, CASE_ID, null));

        assertThat(existing.getStatus()).isEqualTo(originalStatus);
        verify(testCaseRepository, never()).save(any());
    }

    @Test
    void nullStatusPreservesMissingProjectSuiteAndCasePrecedence() {
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(false);
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.updateStatus(PROJECT_ID, SUITE_ID, CASE_ID, null));
        verifyNoInteractions(automationSuiteRepository, testCaseRepository);

        org.mockito.Mockito.reset(projectRepository, automationSuiteRepository, testCaseRepository);
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(true);
        when(automationSuiteRepository.findByProjectIdAndIdForUpdate(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.empty());
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.updateStatus(PROJECT_ID, SUITE_ID, CASE_ID, null));
        verifyNoInteractions(testCaseRepository);

        org.mockito.Mockito.reset(projectRepository, automationSuiteRepository, testCaseRepository);
        stubLockedSuite();
        when(testCaseRepository.findByAutomationSuiteIdAndIdForUpdate(SUITE_ID, CASE_ID))
                .thenReturn(Optional.empty());
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.updateStatus(PROJECT_ID, SUITE_ID, CASE_ID, null));
        verify(testCaseRepository, never()).save(any());
    }

    @Test
    void statusRejectsMissingOrCrossSuiteCaseWithoutSaving() {
        stubLockedSuite();
        when(testCaseRepository.findByAutomationSuiteIdAndIdForUpdate(SUITE_ID, CASE_ID))
                .thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() ->
                service.updateStatus(
                        PROJECT_ID, SUITE_ID, CASE_ID, AutomationTestCaseStatus.ACTIVE));
        verify(testCaseRepository, never()).save(any());
    }

    @Test
    void deleteLocksOwnershipAndPhysicallyDeletesOnlyScopedCase() {
        AutomationTestCase existing = persistedCase(CASE_ID, 0);
        stubLockedSuite();
        when(testCaseRepository.findByAutomationSuiteIdAndIdForUpdate(SUITE_ID, CASE_ID))
                .thenReturn(Optional.of(existing));

        service.delete(PROJECT_ID, SUITE_ID, CASE_ID);

        verify(testCaseRepository).delete(existing);
        verify(testCaseRepository).flush();
    }

    @Test
    void deleteOwnershipFailuresNeverDeleteOrFlush() {
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(false);
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.delete(PROJECT_ID, SUITE_ID, CASE_ID));
        verifyNoInteractions(automationSuiteRepository, testCaseRepository);

        org.mockito.Mockito.reset(projectRepository, automationSuiteRepository, testCaseRepository);
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(true);
        when(automationSuiteRepository.findByProjectIdAndIdForUpdate(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.empty());
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.delete(PROJECT_ID, SUITE_ID, CASE_ID));
        verifyNoInteractions(testCaseRepository);

        org.mockito.Mockito.reset(projectRepository, automationSuiteRepository, testCaseRepository);
        stubLockedSuite();
        when(testCaseRepository.findByAutomationSuiteIdAndIdForUpdate(SUITE_ID, CASE_ID))
                .thenReturn(Optional.empty());
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.delete(PROJECT_ID, SUITE_ID, CASE_ID));
        verify(testCaseRepository, never()).delete(any());
        verify(testCaseRepository, never()).flush();
    }

    @Test
    void reorderAssignsRequestOrderAndReturnsCompletePersistedOrdering() {
        AutomationTestCase first = persistedCase(CASE_ID, 0);
        AutomationTestCase second = persistedCase(SECOND_CASE_ID, 1);
        stubLockedSuite();
        when(testCaseRepository.findAllByAutomationSuiteIdForUpdate(SUITE_ID))
                .thenReturn(List.of(first, second));
        when(testCaseRepository.findByAutomationSuiteIdOrderByPositionAscIdAsc(SUITE_ID))
                .thenReturn(List.of(second, first));

        List<AutomationTestCase> result = service.reorder(
                PROJECT_ID, SUITE_ID, List.of(SECOND_CASE_ID, CASE_ID));

        assertThat(second.getPosition()).isZero();
        assertThat(first.getPosition()).isEqualTo(1);
        assertThat(result).containsExactly(second, first);
        verify(testCaseRepository).saveAllAndFlush(List.of(second, first));
    }

    @Test
    void reorderAcceptsEmptySuiteAndRejectsEveryInvalidMembershipShapeWithoutSaving() {
        stubLockedSuite();
        when(testCaseRepository.findAllByAutomationSuiteIdForUpdate(SUITE_ID))
                .thenReturn(List.of());
        assertThat(service.reorder(PROJECT_ID, SUITE_ID, List.of())).isEmpty();
        verify(testCaseRepository, never()).saveAllAndFlush(
                org.mockito.ArgumentMatchers.<AutomationTestCase>anyList());

        assertInvalidReorder(null);
        assertInvalidReorder(java.util.Arrays.asList((UUID) null));
    }

    @Test
    void reorderRejectsDuplicateMissingExtraAndForeignIdsBeforeSaving() {
        AutomationTestCase first = persistedCase(CASE_ID, 0);
        AutomationTestCase second = persistedCase(SECOND_CASE_ID, 1);
        stubLockedSuite();
        when(testCaseRepository.findAllByAutomationSuiteIdForUpdate(SUITE_ID))
                .thenReturn(List.of(first, second));

        assertInvalidReorder(List.of());
        assertInvalidReorder(List.of(CASE_ID, CASE_ID));
        assertInvalidReorder(List.of(CASE_ID));
        assertInvalidReorder(List.of(CASE_ID, SECOND_CASE_ID, UUID.randomUUID()));
        assertInvalidReorder(List.of(CASE_ID, UUID.randomUUID()));
        verify(testCaseRepository, never()).saveAllAndFlush(
                org.mockito.ArgumentMatchers.<AutomationTestCase>anyList());
    }

    private void assertInvalidReorder(List<UUID> ids) {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.reorder(PROJECT_ID, SUITE_ID, ids));
    }

    private void stubLockedSuite() {
        when(projectRepository.existsById(PROJECT_ID)).thenReturn(true);
        when(automationSuiteRepository.findByProjectIdAndIdForUpdate(PROJECT_ID, SUITE_ID))
                .thenReturn(Optional.of(suite()));
    }

    private AutomationSuite suite() {
        AutomationSuite suite = new AutomationSuite();
        suite.setId(SUITE_ID);
        return suite;
    }

    private AutomationTestCase testCase(String name, String reference, int position) {
        AutomationTestCase testCase = new AutomationTestCase();
        testCase.setName(name);
        testCase.setCaseReference(reference);
        testCase.setPosition(position);
        return testCase;
    }

    private AutomationTestCase persistedCase(UUID id, int position) {
        AutomationTestCase testCase = testCase("Case " + id, "ref-" + id, position);
        testCase.setId(id);
        testCase.setAutomationSuite(suite());
        return testCase;
    }
}
