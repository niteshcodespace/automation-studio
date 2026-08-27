package com.automationstudio.engine.selenium;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ContainmentDependencyDag<N> {
    private final Map<N, Set<N>> prerequisites;

    ContainmentDependencyDag(Map<N, Set<N>> prerequisites) {
        Objects.requireNonNull(prerequisites, "prerequisites");
        var copy = new HashMap<N, Set<N>>();
        prerequisites.forEach((node, required) -> copy.put(Objects.requireNonNull(node, "node"),
                Set.copyOf(Objects.requireNonNull(required, "prerequisites for " + node))));
        copy.values().forEach(required -> required.forEach(node -> {
            if (!copy.containsKey(node)) throw new IllegalArgumentException("Unknown dependency: " + node);
        }));
        rejectCycles(copy);
        this.prerequisites = Map.copyOf(copy);
    }

    EligibilityDecision<N> eligibility(N node, DependencyEvidenceSnapshot<N> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Set<N> required = prerequisites.get(Objects.requireNonNull(node, "node"));
        if (required == null) throw new IllegalArgumentException("Unknown node: " + node);
        boolean unknown = false;
        for (N dependency : required) {
            DependencyEvidence state = snapshot.evidence().getOrDefault(
                    dependency, DependencyEvidence.UNKNOWN);
            if (state == DependencyEvidence.DISPROVED) {
                return new EligibilityDecision<>(node, snapshot.revision(), DependencyEvidence.DISPROVED);
            }
            if (state == DependencyEvidence.UNKNOWN) unknown = true;
        }
        return new EligibilityDecision<>(node, snapshot.revision(),
                unknown ? DependencyEvidence.UNKNOWN : DependencyEvidence.SATISFIED);
    }

    Set<N> prerequisites(N node) {
        Set<N> result = prerequisites.get(Objects.requireNonNull(node, "node"));
        if (result == null) throw new IllegalArgumentException("Unknown node: " + node);
        return result;
    }

    private static <N> void rejectCycles(Map<N, Set<N>> graph) {
        var permanent = new HashSet<N>();
        var temporary = new HashSet<N>();
        for (N node : graph.keySet()) visit(node, graph, permanent, temporary, new ArrayDeque<>());
    }

    private static <N> void visit(N node, Map<N, Set<N>> graph, Set<N> permanent,
            Set<N> temporary, ArrayDeque<N> path) {
        if (permanent.contains(node)) return;
        if (!temporary.add(node)) throw new IllegalArgumentException("Dependency cycle: " + path);
        path.push(node);
        for (N dependency : graph.get(node)) visit(dependency, graph, permanent, temporary, path);
        path.pop();
        temporary.remove(node);
        permanent.add(node);
    }
}
