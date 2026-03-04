package com.eventplatform.scheduling.service;

import com.eventplatform.scheduling.domain.ShowSlotPricingTier;
import com.eventplatform.scheduling.repository.ShowSlotPricingTierRepository;
import com.eventplatform.scheduling.repository.ShowSlotRepository;
import com.eventplatform.shared.common.dto.PricingTierDto;
import com.eventplatform.shared.common.exception.ResourceNotFoundException;
import com.eventplatform.shared.common.service.SlotPricingReader;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SlotPricingReaderImpl implements SlotPricingReader {

    private final ShowSlotRepository showSlotRepository;
    private final ShowSlotPricingTierRepository showSlotPricingTierRepository;

    public SlotPricingReaderImpl(
        ShowSlotRepository showSlotRepository,
        ShowSlotPricingTierRepository showSlotPricingTierRepository
    ) {
        this.showSlotRepository = showSlotRepository;
        this.showSlotPricingTierRepository = showSlotPricingTierRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PricingTierDto> getSlotPricing(Long slotId) {
        if (!showSlotRepository.existsById(slotId)) {
            throw new ResourceNotFoundException("Slot not found: " + slotId, "SLOT_NOT_FOUND");
        }

        List<ShowSlotPricingTier> tiers = showSlotPricingTierRepository.findBySlotId(slotId);
        if (tiers.isEmpty()) {
            return List.of();
        }

        return tiers.stream()
            .map(tier -> new PricingTierDto(
                tier.getId(),
                tier.getName(),
                tier.getPrice(),
                tier.getQuota(),
                tier.getTierType().name(),
                tier.getEbTicketClassId(),
                tier.getEbInventoryTierId(),
                tier.getGroupDiscountThreshold(),
                tier.getGroupDiscountPercent()
            ))
            .toList();
    }
}
