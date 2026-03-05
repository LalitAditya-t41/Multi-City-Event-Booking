package com.eventplatform.bookinginventory.service;

import com.eventplatform.bookinginventory.api.dto.response.AvailableSeatResponse;
import com.eventplatform.bookinginventory.api.dto.response.AvailableSeatsEnvelopeResponse;
import com.eventplatform.bookinginventory.api.dto.response.GaTierAvailabilityResponse;
import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import com.eventplatform.bookinginventory.mapper.SeatMapper;
import com.eventplatform.bookinginventory.repository.GaInventoryClaimRepository;
import com.eventplatform.bookinginventory.repository.SeatRepository;
import com.eventplatform.bookinginventory.service.redis.GaInventoryRedisService;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.dto.PricingTierDto;
import com.eventplatform.shared.common.dto.SlotSummaryDto;
import com.eventplatform.shared.common.enums.SeatingMode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SeatAvailabilityService {

  private final SlotValidationService slotValidationService;
  private final SeatRepository seatRepository;
  private final CartPricingService cartPricingService;
  private final SeatMapper seatMapper;
  private final GaInventoryRedisService gaInventoryRedisService;
  private final GaInventoryClaimRepository gaInventoryClaimRepository;
  private final StringRedisTemplate stringRedisTemplate;

  public SeatAvailabilityService(
      SlotValidationService slotValidationService,
      SeatRepository seatRepository,
      CartPricingService cartPricingService,
      SeatMapper seatMapper,
      GaInventoryRedisService gaInventoryRedisService,
      GaInventoryClaimRepository gaInventoryClaimRepository,
      StringRedisTemplate stringRedisTemplate) {
    this.slotValidationService = slotValidationService;
    this.seatRepository = seatRepository;
    this.cartPricingService = cartPricingService;
    this.seatMapper = seatMapper;
    this.gaInventoryRedisService = gaInventoryRedisService;
    this.gaInventoryClaimRepository = gaInventoryClaimRepository;
    this.stringRedisTemplate = stringRedisTemplate;
  }

  public AvailableSeatsEnvelopeResponse getAvailability(Long slotId) {
    SlotSummaryDto slot = slotValidationService.requireActiveAndSynced(slotId);
    List<PricingTierDto> tiers = cartPricingService.getSlotPricingCached(slotId);
    Map<Long, PricingTierDto> tierMap =
        tiers.stream().collect(Collectors.toMap(PricingTierDto::tierId, Function.identity()));

    if (slot.seatingMode() == SeatingMode.RESERVED) {
      List<AvailableSeatResponse> seats =
          seatRepository.findAvailableForSlot(slotId, java.time.Instant.now()).stream()
              .map(
                  seat -> {
                    PricingTierDto tier = tierMap.get(seat.getPricingTierId());
                    String tierName = tier == null ? null : tier.tierName();
                    BigDecimal amount = tier == null ? BigDecimal.ZERO : tier.price().amount();
                    String currency = tier == null ? "INR" : tier.price().currency();
                    return seatMapper.toAvailableSeatResponse(seat, tierName, amount, currency);
                  })
              .toList();
      return new AvailableSeatsEnvelopeResponse(slotId, slot.seatingMode(), seats, List.of());
    }

    List<GaTierAvailabilityResponse> gaTiers =
        tiers.stream()
            .map(
                tier -> {
                  Long available = gaInventoryRedisService.getCurrent(slotId, tier.tierId());
                  if (available == null) {
                    long confirmed =
                        gaInventoryClaimRepository.countByShowSlotIdAndPricingTierIdAndLockState(
                            slotId, tier.tierId(), SeatLockState.CONFIRMED);
                    available = Math.max(0, tier.quota() - confirmed);
                  }
                  boolean blocked =
                      Boolean.TRUE.equals(
                          stringRedisTemplate.hasKey(
                              "tier:blocked:" + slotId + ":" + tier.tierId()));
                  return new GaTierAvailabilityResponse(
                      tier.tierId(),
                      tier.tierName(),
                      tier.quota(),
                      available,
                      new Money(tier.price().amount(), tier.price().currency()),
                      blocked);
                })
            .toList();

    return new AvailableSeatsEnvelopeResponse(slotId, slot.seatingMode(), List.of(), gaTiers);
  }
}
