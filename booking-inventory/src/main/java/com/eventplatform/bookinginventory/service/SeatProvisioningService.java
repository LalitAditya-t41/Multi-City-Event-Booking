package com.eventplatform.bookinginventory.service;

import com.eventplatform.bookinginventory.domain.GroupDiscountRule;
import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.repository.GroupDiscountRuleRepository;
import com.eventplatform.bookinginventory.repository.SeatRepository;
import com.eventplatform.bookinginventory.service.client.CatalogSeatLayoutClient;
import com.eventplatform.bookinginventory.service.client.CatalogSeatResponse;
import com.eventplatform.shared.common.dto.PricingTierDto;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.common.service.SlotPricingReader;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SeatProvisioningService {

    private final SeatRepository seatRepository;
    private final GroupDiscountRuleRepository groupDiscountRuleRepository;
    private final SlotPricingReader slotPricingReader;
    private final CatalogSeatLayoutClient catalogSeatLayoutClient;
    private final GaInventoryService gaInventoryService;

    public SeatProvisioningService(
        SeatRepository seatRepository,
        GroupDiscountRuleRepository groupDiscountRuleRepository,
        SlotPricingReader slotPricingReader,
        CatalogSeatLayoutClient catalogSeatLayoutClient,
        GaInventoryService gaInventoryService
    ) {
        this.seatRepository = seatRepository;
        this.groupDiscountRuleRepository = groupDiscountRuleRepository;
        this.slotPricingReader = slotPricingReader;
        this.catalogSeatLayoutClient = catalogSeatLayoutClient;
        this.gaInventoryService = gaInventoryService;
    }

    @Transactional
    public void provision(Long slotId, Long venueId, SeatingMode seatingMode) {
        List<PricingTierDto> tiers = slotPricingReader.getSlotPricing(slotId);
        provisionGroupDiscountRules(slotId, tiers);

        if (seatingMode == SeatingMode.GA) {
            gaInventoryService.initCounters(slotId, tiers);
            return;
        }

        if (seatRepository.existsByShowSlotId(slotId)) {
            return;
        }

        Map<String, PricingTierDto> tierByName = tiers.stream()
            .collect(Collectors.toMap(t -> normalize(t.tierName()), Function.identity(), (a, b) -> a));

        List<CatalogSeatResponse> venueSeats = catalogSeatLayoutClient.getSeatLayout(venueId).seats();
        List<Seat> seats = venueSeats.stream()
            .map(venueSeat -> {
                PricingTierDto tier = tierByName.get(normalize(venueSeat.tierName()));
                if (tier == null) {
                    return null;
                }
                return new Seat(
                    slotId,
                    tier.tierId(),
                    tier.ebTicketClassId(),
                    venueSeat.seatNumber(),
                    venueSeat.rowLabel(),
                    venueSeat.section()
                );
            })
            .filter(java.util.Objects::nonNull)
            .toList();

        seatRepository.saveAll(seats);
    }

    private void provisionGroupDiscountRules(Long slotId, List<PricingTierDto> tiers) {
        if (!groupDiscountRuleRepository.findByShowSlotId(slotId).isEmpty()) {
            return;
        }
        List<GroupDiscountRule> rules = tiers.stream()
            .map(tier -> new GroupDiscountRule(
                slotId,
                tier.tierId(),
                tier.groupDiscountThreshold() == null ? Integer.MAX_VALUE : tier.groupDiscountThreshold(),
                tier.groupDiscountPercent() == null ? BigDecimal.ZERO : tier.groupDiscountPercent()
            ))
            .toList();
        groupDiscountRuleRepository.saveAll(rules);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
