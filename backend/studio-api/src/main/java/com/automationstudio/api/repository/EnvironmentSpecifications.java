package com.automationstudio.api.repository;

import com.automationstudio.api.domain.EnvironmentStatus;
import com.automationstudio.api.domain.EnvironmentType;
import com.automationstudio.api.entity.Environment;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class EnvironmentSpecifications {

    private EnvironmentSpecifications() {
    }

    public static Specification<Environment> withFilters(
            UUID projectId,
            EnvironmentStatus status,
            EnvironmentType type,
            Boolean isDefault) {
        Specification<Environment> specification =
                (root, query, builder) -> builder.equal(root.get("project").get("id"), projectId);
        if (status != null) {
            specification = specification.and(
                    (root, query, builder) -> builder.equal(root.get("status"), status));
        }
        if (type != null) {
            specification = specification.and(
                    (root, query, builder) -> builder.equal(root.get("type"), type));
        }
        if (isDefault != null) {
            specification = specification.and(
                    (root, query, builder) ->
                            builder.equal(root.get("isDefault"), isDefault));
        }
        return specification;
    }
}
