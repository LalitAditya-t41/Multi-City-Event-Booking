package com.eventplatform.bookinginventory.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.bookinginventory.repository.GroupDiscountRuleRepository;
import com.eventplatform.bookinginventory.repository.SeatRepository;
import com.eventplatform.bookinginventory.service.client.CatalogSeatLayoutClient;
import com.eventplatform.bookinginventory.service.client.CatalogSeatLayoutResponse;
import com.eventplatform.bookinginventory.service.client.CatalogSeatResponse;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.dto.PricingTierDto;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.common.service.SlotPricingReader;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatProvisioningServiceTest {

  @Mock private SeatRepository seatRepository;
  @Mock private GroupDiscountRuleRepository groupDiscountRuleRepository;
  @Mock private SlotPricingReader slotPricingReader;
  @Mock private CatalogSeatLayoutClient catalogSeatLayoutClient;
  @Mock private GaInventoryService gaInventoryService;

  @InjectMocks private SeatProvisioningService seatProvisioningService;

  @Test
  void should_bulk_insert_seats_from_venue_seat_layout_on_slot_activated() {
    when(slotPricingReader.getSlotPricing(10L)).thenReturn(List.of(tier("VIP", 100L)));
    when(groupDiscountRuleRepository.findByShowSlotId(10L)).thenReturn(List.of());
    when(seatRepository.existsByShowSlotId(10L)).thenReturn(false);
    when(catalogSeatLayoutClient.getSeatLayout(20L))
        .thenReturn(
            new CatalogSeatLayoutResponse(
                20L, 1, List.of(new CatalogSeatResponse(1L, "S1", "A", "A1", "VIP", false))));

    seatProvisioningService.provision(10L, 20L, SeatingMode.RESERVED);

    verify(seatRepository).saveAll(any());
  }

  @Test
  void should_be_idempotent_when_seats_already_exist_for_slot() {
    when(slotPricingReader.getSlotPricing(11L)).thenReturn(List.of(tier("VIP", 100L)));
    when(groupDiscountRuleRepository.findByShowSlotId(11L)).thenReturn(List.of());
    when(seatRepository.existsByShowSlotId(11L)).thenReturn(true);

    seatProvisioningService.provision(11L, 21L, SeatingMode.RESERVED);

    verify(catalogSeatLayoutClient, never()).getSeatLayout(any());
    verify(seatRepository, never()).saveAll(any());
  }

  @Test
  void should_init_ga_redis_counters_from_tier_quotas_on_ga_slot_activated() {
    List<PricingTierDto> tiers = List.of(tier("GA", 50L), tier("VIP", 75L));
    when(slotPricingReader.getSlotPricing(12L)).thenReturn(tiers);
    when(groupDiscountRuleRepository.findByShowSlotId(12L)).thenReturn(List.of());

    seatProvisioningService.provision(12L, 22L, SeatingMode.GA);

    verify(gaInventoryService).initCounters(12L, tiers);
    verify(seatRepository, never()).saveAll(any());
  }

  private PricingTierDto tier(String name, Long id) {
    return new PricingTierDto(
        id,
        name,
        new Money(new BigDecimal("500.00"), "INR"),
        100,
        "PAID",
        "tc_" + id,
        "inv_" + id,
        4,
        new BigDecimal("10.00"));
  }
}
