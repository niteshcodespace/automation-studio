# AS-031D2b deterministic verification traceability

| Requirement | Named test evidence |
|---|---|
| N01, N14 | `n01SuccessfulCreateInspectHandoffAndN14ExactIdRemovalAbsence`; `DockerCliNetworkControlPlaneTest.exactIdOperationsAndRealNotFoundClassificationAreStrict`; `representativeDockerInspectIntegratesWithCanonicalParser` |
| N02 | `n02PreDispatchDefiniteFailureHasNoSideEffect` |
| N03, N04 | `n03PossibleDispatchLostResponseAndN04MalformedIdRemainUnsafe` |
| N05, N06 | `n05LookalikeWrongNonceAndN06PreExistingNameAreNeverAdopted` |
| N07 | `n07EveryAuthoritativeFingerprintFieldRejectsMismatch` |
| N08, N09 | `n08ReplayAndN09DuplicateConsumedAttemptAreRejected` |
| N10, N11 | `n10SameIdConflictAndN11DifferentIdsRetainAllIdentities`; `n10CrossRoleSameImmutableIdRetainsBothTypedIdentitiesWorkerThenNetwork`; `n10CrossRoleSameImmutableIdRetainsBothTypedIdentitiesNetworkThenWorker`; `concurrentSameIdHandoffsAreIdempotentAndRetainOneReachableIdentity`; `concurrentDifferentIdHandoffsRetainEveryReachableIdentity` |
| N12, N13 | `n12DisappearingCandidateAndN13DaemonChangeRemainUnsafe` |
| N15 | `n15AmbiguousRemoveOrAbsenceCannotManufactureAbsence` |
| N16, N17 | `n16WorkerDependencyPrecedesNetworkAndN17PartialStartupUsesReverseIdOrder` |
| N18, N23 | `n18DeadlineExhaustionRetainsIdentityAndN23OneOriginalDeadlineEverywhere` |
| N19 | `n19TerminalPublicationRacingLateHandoffCannotBecomeSafe` |
| N20, N21, N22 | `n20AuthorityIsNonForgeableN21SurfaceIsClosedN22SocketAndWorkerNetworkStayClosed` |
| N24 | Scope audit in implementation report plus `git diff --name-only 6a91ce2` |
| Inspect framing, normal empty `ConfigFrom`, duplicate keys, authoritative-map negatives | `parserRejectsDuplicateZeroMultipleConcatenatedTrailingTruncatedAndUnexpectedAuthority`; `DockerCliNetworkControlPlaneTest.representativeDockerInspectIntegratesWithCanonicalParser` |
| Daemon continuity at recovery, inspect, remove, and absence | `n12DisappearingCandidateAndN13DaemonChangeRemainUnsafe`, `daemonContinuityChangesAtRecoveryRemoveAndAbsenceRemainUnsafe` |
| Continue after independent retained-ID failure | `cleanupContinuesAfterIndependentIdFailureAndDoesNotHideIt` |
| One-shot remove and absence-only resumption after inconclusive absence | `lateReconciliationResumesAbsenceWithoutRepeatingSuccessfulRemove` |
| Failed/ambiguous remove is never retried or masked | `failedRemoveIsNeverRetriedOrMaskedByLaterReconciliation` |
| C1-C13 acquisition/recovery/handoff/dependency/cleanup/publication boundaries | `DockerNetworkAuthorityTest.everyBoundaryUsesDeterministicLatchAndPreservesOneDeadline`; `dependencyRegistrationRacesCleanupAsOneSerializedMutation`; `n19TerminalPublicationRacingLateHandoffCannotBecomeSafe`; `lateOwnershipRacingActiveCleanupIsRetainedAndReconciledOnce` |
| Concurrent same-ID and different-ID handoffs | `concurrentSameIdHandoffsAreIdempotentAndRetainOneReachableIdentity`; `concurrentDifferentIdHandoffsRetainEveryReachableIdentity` |
| Cross-role same-string collision, both arrival orders and typed cleanup reachability | `n10CrossRoleSameImmutableIdRetainsBothTypedIdentitiesWorkerThenNetwork`; `n10CrossRoleSameImmutableIdRetainsBothTypedIdentitiesNetworkThenWorker` |
| Closed adapter argv/recovery surface | `DockerCliNetworkControlPlaneTest.closedSurfaceBuildsOnlyFrozenCreateAndRecoveryArgv` |
| Typed transport, bounded output, timeout/interruption/read failure and reader/process cleanup | `DockerCliNetworkControlPlaneTest.transportDistinctionsBoundedOutputAndContinuityArePreserved`; `realProcessRunnerBoundsOutputAndCleansUpTimeout`; `realProcessRunnerInterruptsAfterDispatchAndCleansProcessAndReader`; `productionRunnerClassifiesDeterministicReadFailure` |
| Deadline expiry at verification/handoff/remove/absence boundaries | `deadlineExpiryAtEveryVerificationAndDestructiveBoundaryFailsClosed` |

The named tests use explicit latches/barriers for concurrency boundaries. No concurrency claim relies on sleeps or timing-only assertions.
