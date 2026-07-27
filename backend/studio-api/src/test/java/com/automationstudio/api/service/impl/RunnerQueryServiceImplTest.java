package com.automationstudio.api.service.impl;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.automationstudio.api.config.RunnerHealthProperties;
import com.automationstudio.api.domain.RunnerHealth;
import com.automationstudio.api.domain.RunnerStatus;
import com.automationstudio.api.exception.InvalidRequestException;
import com.automationstudio.api.repository.RunnerDiscoveryRepository;
import com.automationstudio.api.repository.RunnerRepository;
import com.automationstudio.api.repository.RunnerRuntimeRepository;
import com.automationstudio.api.service.RunnerHeartbeatService;
import com.automationstudio.api.service.query.RunnerQueryFilter;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class RunnerQueryServiceImplTest {

    private static final Instant DATABASE_TIME =
            Instant.parse("2026-07-26T10:00:00Z");

    @Mock
    private RunnerRepository runnerRepository;
    @Mock
    private RunnerRuntimeRepository runtimeRepository;
    @Mock
    private RunnerDiscoveryRepository discoveryRepository;
    @Mock
    private RunnerHeartbeatService heartbeatService;

    private RunnerQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RunnerQueryServiceImpl(
                runnerRepository,
                runtimeRepository,
                discoveryRepository,
                heartbeatService,
                new RunnerHealthProperties(Duration.ofMinutes(1), Duration.ofMinutes(5)));
    }

    @Test
    void delegatesValidatedCombinedFilterWithOneDatabaseTime() {
        RunnerQueryFilter filter = new RunnerQueryFilter(
                RunnerStatus.ACTIVE,
                RunnerHealth.ONLINE,
                true,
                " playwright-java ",
                " linux ");
        var pageable = PageRequest.of(1, 10, Sort.by(Sort.Order.desc("lastSeenAt")));
        when(runnerRepository.currentDatabaseTime()).thenReturn(DATABASE_TIME);
        when(discoveryRepository.findRunnerIds(
                new RunnerQueryFilter(
                        RunnerStatus.ACTIVE,
                        RunnerHealth.ONLINE,
                        true,
                        "playwright-java",
                        "linux"),
                OffsetDateTime.ofInstant(DATABASE_TIME, ZoneOffset.UTC),
                Duration.ofMinutes(1),
                Duration.ofMinutes(5),
                pageable)).thenReturn(new PageImpl<UUID>(List.of(), pageable, 0));

        service.list(filter, pageable);

        verify(runnerRepository).currentDatabaseTime();
        verify(discoveryRepository).findRunnerIds(
                new RunnerQueryFilter(
                        RunnerStatus.ACTIVE,
                        RunnerHealth.ONLINE,
                        true,
                        "playwright-java",
                        "linux"),
                OffsetDateTime.ofInstant(DATABASE_TIME, ZoneOffset.UTC),
                Duration.ofMinutes(1),
                Duration.ofMinutes(5),
                pageable);
    }

    @Test
    void rejectsInvalidFiltersPageSizesSortsAndDirectionsBeforeQuerying() {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.list(
                        new RunnerQueryFilter(null, null, null, " ", null),
                        PageRequest.of(0, 20)));
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.list(
                        new RunnerQueryFilter(null, null, null, null, null),
                        PageRequest.of(0, 101)));
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.list(
                        new RunnerQueryFilter(null, null, null, null, null),
                        PageRequest.of(0, 20, Sort.by("unknown"))));
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.list(
                        new RunnerQueryFilter(null, null, null, null, null),
                        PageRequest.of(0, 20, Sort.by("name")),
                        "sideways"));

        verifyNoInteractions(discoveryRepository, heartbeatService);
    }
}
