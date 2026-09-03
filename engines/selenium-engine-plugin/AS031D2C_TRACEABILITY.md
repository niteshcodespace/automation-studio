# AS-031D2c implementation traceability

AS-031D2c creates a non-executable topology foundation only. It never issues browser or DNS execution eligibility; D2d remains the first slice permitted to establish policy eligibility.

| Approved contract | Implementation / focused evidence |
|---|---|
| Fixed `172.30.0.0/16`, ascending `/28`, atomic two-network reservation | `D2cTopologyAllocator`; `allocatorUsesFrozenPoolOrderAndRejectsOverlapAndIncompleteInventory`; `concurrentGenerationsCannotReserveSameCidrs` |
| Typed worker/egress networks and endpoint nodes | `D2cTopologyModel`; `D2cTopologyAuthority.dependencyDag`; `dependencyDagBlocksNetworksUntilExactEndpointsAreAbsent` |
| One-shot endpoint connect/detach and absence-only resume | `D2cEndpointAuthority`; `endpointConnectAndDetachAreOneShotAndAbsenceOnlyResumes`; `possibleConnectWithoutExactEvidenceRemainsAmbiguous` |
| Exact gateway runtime policy | `D2cGatewaySpec`, `src/gateway/Dockerfile.gateway`, `gateway.c`, `passwd`, `group`; `gatewayFingerprintIsSingularAndRejectsMutableIdentity` |
| One-shot worker route mutation, inspect-only reconciliation | `D2cRouteAuthority`; `routeMutationDispatchesOnceThenReconcilesInspectOnly` |
| Namespace/topology/daemon/helper binding | `D2cRouteAuthority.Binding`; negative validation and foreign-issuer tests |
| Opaque readiness and monotonic loss | `D2cTopologyAuthority.Readiness`; `readinessIsNonforgeableDeadlineBoundAndNeverOpensBrowserEligibility` |
| Original deadline identity and cleanup issuer | all D2c authorities retain the cleanup-owned `ContainmentDeadline` and `ProofIssuer`; focused identity/foreign-authority assertions |
| D2c/D2d boundary | `browserExecutionEligible()` is permanently false; DNS/browser readiness fields reject true |
| Closed production Docker inventory/network/endpoint grammar | strict duplicate-detecting structural documents, exact network-ID handoff, pre/post daemon probes, labeled create attempts, name-independent endpoint membership, and live-container detachment proof in `D2cDockerTopologyAdapter`; focused malformed-ID and live-container tests |
| Gateway acquisition and fixed production argv | cleanup-owned `D2cGatewayAuthority`; complete-label nonce recovery/collision retention, full label/executable fingerprint, exact-ID cleanup; immutable CLI grammar in `D2cCliGatewayAdapter` |
| Fixed namespace helper trust boundary | digest-verifying bounded `ProcessTransport`, fixed environment/IPC and cleanup in `D2cNamespaceHelperLauncher`; packaged capability-minimal helper Makefile; native PID/start/cgroup/netns revalidation and netlink ACK validation |
| Trusted readiness facts | `D2cReadinessAdapter.production` derives completeness only from the cleanup-owned gateway, endpoint, route, daemon, deadline, and canonical-state authorities |
| D2a1 terminal integration | `SingleOwnerCleanup.publishOnce` invokes owner-driven D2c endpoint/gateway/network reconciliation; reservation release is derived from authoritative absence and callbacks follow one lock order |
| Reproducible gateway artifact | Docker build base is pinned to the exact Alpine linux/amd64 manifest; the build requires and verifies the executable SHA-256 before emitting the scratch image label |

Production surfaces are package-private and typed. No caller-controlled Docker argv, shell, route, namespace, capability, mount, sysctl, address, network, or gateway runtime option is introduced.
