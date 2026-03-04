package com.eventplatform.paymentsticketing.repository;

import com.eventplatform.paymentsticketing.domain.CancellationPolicy;
import com.eventplatform.paymentsticketing.domain.enums.CancellationPolicyScope;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CancellationPolicyRepository extends JpaRepository<CancellationPolicy, Long> {

    @EntityGraph(attributePaths = "tiers")
    Optional<CancellationPolicy> findById(Long id);

    @EntityGraph(attributePaths = "tiers")
    Optional<CancellationPolicy> findByOrgId(Long orgId);

    @EntityGraph(attributePaths = "tiers")
    Optional<CancellationPolicy> findByScope(CancellationPolicyScope scope);

    boolean existsByOrgId(Long orgId);
}
