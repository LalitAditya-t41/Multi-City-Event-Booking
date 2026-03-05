package com.eventplatform.bookinginventory.service;

import com.eventplatform.bookinginventory.service.redis.GaInventoryRedisService;
import com.eventplatform.shared.common.dto.PricingTierDto;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GaInventoryService {

  private final GaInventoryRedisService gaInventoryRedisService;

  public GaInventoryService(GaInventoryRedisService gaInventoryRedisService) {
    this.gaInventoryRedisService = gaInventoryRedisService;
  }

  public void initCounters(Long slotId, List<PricingTierDto> tiers) {
    tiers.forEach(tier -> gaInventoryRedisService.init(slotId, tier.tierId(), tier.quota()));
  }
}
