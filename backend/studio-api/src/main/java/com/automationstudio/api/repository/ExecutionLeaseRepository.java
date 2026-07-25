package com.automationstudio.api.repository;

import com.automationstudio.api.entity.ExecutionLease;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionLeaseRepository extends JpaRepository<ExecutionLease, UUID> {

    Optional<ExecutionLease> findByClaimToken(UUID claimToken);

    List<ExecutionLease> findByRunnerId(String runnerId);
}
