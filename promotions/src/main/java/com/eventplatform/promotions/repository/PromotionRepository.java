package com.eventplatform.promotions.repository;

import com.eventplatform.promotions.domain.Promotion;
import com.eventplatform.promotions.domain.enums.PromotionStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {
    List<Promotion> findByOrgId(Long orgId);
    List<Promotion> findByOrgIdAndStatus(Long orgId, PromotionStatus status);
}
