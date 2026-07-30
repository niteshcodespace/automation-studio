package com.automationstudio.api.execution.workspace;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public enum WorkspaceState {
    PLANNED,
    PREPARING,
    READY,
    IN_USE,
    RELEASING,
    RELEASED;

    private static final Map<WorkspaceState, Set<WorkspaceState>> TRANSITIONS =
            transitions();

    public boolean canTransitionTo(WorkspaceState target) {
        return target != null && TRANSITIONS.get(this).contains(target);
    }

    void requireTransitionTo(WorkspaceState target) {
        if (!canTransitionTo(target)) {
            throw new WorkspaceContractException(
                    "Workspace cannot transition from " + this + " to " + target);
        }
    }

    private static Map<WorkspaceState, Set<WorkspaceState>> transitions() {
        Map<WorkspaceState, Set<WorkspaceState>> transitions =
                new EnumMap<>(WorkspaceState.class);
        transitions.put(PLANNED, Set.of(PREPARING));
        transitions.put(PREPARING, Set.of(READY));
        transitions.put(READY, Set.of(IN_USE));
        transitions.put(IN_USE, Set.of(RELEASING));
        transitions.put(RELEASING, Set.of(RELEASED));
        transitions.put(RELEASED, Set.of());
        return Map.copyOf(transitions);
    }
}
