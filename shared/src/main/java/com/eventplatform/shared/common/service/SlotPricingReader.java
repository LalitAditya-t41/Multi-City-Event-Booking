package com.eventplatform.shared.common.service;

import com.eventplatform.shared.common.dto.PricingTierDto;
import java.util.List;

public interface SlotPricingReader {
  List<PricingTierDto> getSlotPricing(Long slotId);
}
