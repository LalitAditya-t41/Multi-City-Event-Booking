package com.eventplatform.promotions.repository;

import com.eventplatform.promotions.domain.DiscountReconciliationLog;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscountReconciliationLogRepository extends JpaRepository<DiscountReconciliationLog, Long> {
    Optional<DiscountReconciliationLog> findTopByOrgIdOrderByRunAtDesc(Long orgId);
}
