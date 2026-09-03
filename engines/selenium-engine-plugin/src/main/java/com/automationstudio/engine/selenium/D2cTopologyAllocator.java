package com.automationstudio.engine.selenium;

import static com.automationstudio.engine.selenium.D2cTopologyModel.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Process-global, deterministic D2c reservation authority. */
final class D2cTopologyAllocator {
    private static final Object LOCK = new Object();
    private static final Ipv4Cidr POOL = Ipv4Cidr.parse("172.30.0.0/16");
    private static final Map<ReservationKey, Ipv4Cidr> ACTIVE = new HashMap<>();

    record Inventory(DockerControlPlane.DockerDaemonIdentity daemon, long revision,
            long topologyGeneration, ContainmentDeadline deadline, Set<Ipv4Cidr> subnets,
            boolean complete) {
        Inventory {
            Objects.requireNonNull(daemon); Objects.requireNonNull(deadline);
            subnets = Set.copyOf(subnets);
            if (revision <= 0 || topologyGeneration <= 0) throw new IllegalArgumentException("Invalid inventory");
        }
    }

    List<Allocation> reserve(UUID executionId, long generation, Inventory inventory) {
        Objects.requireNonNull(executionId); Objects.requireNonNull(inventory);
        if (!inventory.complete() || inventory.deadline().expired() || inventory.topologyGeneration() != generation)
            throw new IllegalStateException("Non-authoritative IPAM inventory");
        synchronized (LOCK) {
            var workerKey = new ReservationKey(executionId, generation, NetworkInstance.WORKER_NETWORK);
            var egressKey = new ReservationKey(executionId, generation, NetworkInstance.EGRESS_NETWORK);
            if (ACTIVE.containsKey(workerKey) || ACTIVE.containsKey(egressKey))
                throw new IllegalStateException("Topology already reserved");
            var blocked = new HashSet<>(inventory.subnets()); blocked.addAll(ACTIVE.values());
            var chosen = new ArrayList<Ipv4Cidr>(2);
            long base = Integer.toUnsignedLong(POOL.network());
            for (int offset = 0; offset < 65536 && chosen.size() < 2; offset += 16) {
                var candidate = new Ipv4Cidr((int) (base + offset), 28);
                if (blocked.stream().noneMatch(candidate::overlaps)) { chosen.add(candidate); blocked.add(candidate); }
            }
            if (chosen.size() != 2) throw new IllegalStateException("D2c address pool exhausted");
            ACTIVE.put(workerKey, chosen.get(0)); ACTIVE.put(egressKey, chosen.get(1));
            return List.of(allocation(workerKey, chosen.get(0), 3), allocation(egressKey, chosen.get(1), 2));
        }
    }

    void release(ReservationKey key, boolean networkAbsent, boolean endpointsAbsent) {
        Objects.requireNonNull(key);
        synchronized (LOCK) {
            if (!networkAbsent || !endpointsAbsent) throw new IllegalStateException("Unresolved reservation");
            ACTIVE.remove(key);
        }
    }

    boolean active(ReservationKey key, Ipv4Cidr subnet) {
        synchronized (LOCK) { return Objects.equals(ACTIVE.get(key), subnet); }
    }

    static void clearForTesting() { synchronized (LOCK) { ACTIVE.clear(); } }
    private static Allocation allocation(ReservationKey key, Ipv4Cidr cidr, int endpointCount) {
        var addresses = new ArrayList<String>(); for (int i = 0; i < endpointCount; i++) addresses.add(cidr.address(i + 2));
        return new Allocation(key, cidr, cidr.address(1), addresses);
    }
}
