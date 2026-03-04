package com.eventplatform.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.eventplatform.scheduling.domain.ShowSlotPricingTier;
import com.eventplatform.scheduling.domain.enums.TierType;
import com.eventplatform.scheduling.repository.ShowSlotPricingTierRepository;
import com.eventplatform.scheduling.repository.ShowSlotRepository;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.dto.PricingTierDto;
import com.eventplatform.shared.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SlotPricingReaderImplTest {

    @Mock
    private ShowSlotRepository showSlotRepository;

    @Mock
    private ShowSlotPricingTierRepository showSlotPricingTierRepository;

    @InjectMocks
    private SlotPricingReaderImpl slotPricingReader;

    @Test
    void should_return_mapped_list_of_PricingTierDto_when_tiers_exist() {
        ShowSlotPricingTier tier = new ShowSlotPricingTier(
            "VIP",
            new Money(new BigDecimal("1500.00"), "INR"),
            50,
            TierType.PAID
        );
        ReflectionTestUtils.setField(tier, "id", 501L);
        tier.setEbTicketClassId("tc_501");
        tier.setEbInventoryTierId("inv_501");
        tier.setGroupDiscount(4, new BigDecimal("10.00"));

        when(showSlotRepository.existsById(11L)).thenReturn(true);
        when(showSlotPricingTierRepository.findBySlotId(11L)).thenReturn(List.of(tier));

        List<PricingTierDto> result = slotPricingReader.getSlotPricing(11L);

        assertThat(result).hasSize(1);
        PricingTierDto dto = result.getFirst();
        assertThat(dto.tierId()).isEqualTo(501L);
        assertThat(dto.tierName()).isEqualTo("VIP");
        assertThat(dto.price().amount()).isEqualByComparingTo("1500.00");
        assertThat(dto.price().currency()).isEqualTo("INR");
        assertThat(dto.ebTicketClassId()).isEqualTo("tc_501");
    }

    @Test
    void should_return_empty_list_when_no_tiers_for_slot() {
        when(showSlotRepository.existsById(12L)).thenReturn(true);
        when(showSlotPricingTierRepository.findBySlotId(12L)).thenReturn(List.of());

        List<PricingTierDto> result = slotPricingReader.getSlotPricing(12L);

        assertThat(result).isEmpty();
    }

    @Test
    void should_map_tierId_tierName_price_groupDiscount_correctly() {
        ShowSlotPricingTier tier = new ShowSlotPricingTier(
            "Group",
            new Money(new BigDecimal("600.00"), "INR"),
            200,
            TierType.PAID
        );
        ReflectionTestUtils.setField(tier, "id", 777L);
        tier.setGroupDiscount(6, new BigDecimal("15.50"));

        when(showSlotRepository.existsById(13L)).thenReturn(true);
        when(showSlotPricingTierRepository.findBySlotId(13L)).thenReturn(List.of(tier));

        PricingTierDto dto = slotPricingReader.getSlotPricing(13L).getFirst();

        assertThat(dto.tierId()).isEqualTo(777L);
        assertThat(dto.tierName()).isEqualTo("Group");
        assertThat(dto.price().amount()).isEqualByComparingTo("600.00");
        assertThat(dto.groupDiscountThreshold()).isEqualTo(6);
        assertThat(dto.groupDiscountPercent()).isEqualByComparingTo("15.50");
    }

    @Test
    void should_throw_ResourceNotFoundException_when_slot_does_not_exist() {
        when(showSlotRepository.existsById(404L)).thenReturn(false);

        assertThatThrownBy(() -> slotPricingReader.getSlotPricing(404L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Slot not found: 404");
    }
}
