package com.eventplatform.paymentsticketing.repository;

import com.eventplatform.paymentsticketing.domain.CancellationPolicyTier;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CancellationPolicyTierRepository extends JpaRepository<CancellationPolicyTier, Long> {

    List<CancellationPolicyTier> findByPolicyIdOrderBySortOrderAsc(Long policyId);
}
