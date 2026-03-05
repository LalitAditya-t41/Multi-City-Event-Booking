package com.eventplatform.bookinginventory.repository;

import com.eventplatform.bookinginventory.domain.GroupDiscountRule;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupDiscountRuleRepository extends JpaRepository<GroupDiscountRule, Long> {

  Optional<GroupDiscountRule> findByShowSlotIdAndPricingTierId(Long showSlotId, Long pricingTierId);

  List<GroupDiscountRule> findByShowSlotId(Long showSlotId);
}
