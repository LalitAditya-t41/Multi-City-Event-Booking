package com.eventplatform.bookinginventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.bookinginventory.api.dto.response.AvailableSeatResponse;
import com.eventplatform.bookinginventory.api.dto.response.AvailableSeatsEnvelopeResponse;
import com.eventplatform.bookinginventory.domain.Seat;
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
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SeatAvailabilityServiceTest {

    @Mock
    private SlotValidationService slotValidationService;
    @Mock
    private SeatRepository seatRepository;
    @Mock
    private CartPricingService cartPricingService;
    @Mock
    private SeatMapper seatMapper;
    @Mock
    private GaInventoryRedisService gaInventoryRedisService;
    @Mock
    private GaInventoryClaimRepository gaInventoryClaimRepository;
    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private SeatAvailabilityService seatAvailabilityService;

    @Test
    void should_return_available_seats_excluding_non_expired_locks() {
        SlotSummaryDto slot = new SlotSummaryDto(11L, "ACTIVE", "eb-11", SeatingMode.RESERVED, 1L, 2L, 3L, null);
        PricingTierDto tier = tier();
        Seat seat = seat(100L, SeatLockState.AVAILABLE);
        AvailableSeatResponse mapped = new AvailableSeatResponse(100L, "A1", "A", "S1", 10L, "VIP",
            new Money(new BigDecimal("500.00"), "INR"), SeatLockState.AVAILABLE);

        when(slotValidationService.requireActiveAndSynced(11L)).thenReturn(slot);
        when(cartPricingService.getSlotPricingCached(11L)).thenReturn(List.of(tier));
        when(seatRepository.findAvailableForSlot(org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.any(Instant.class)))
            .thenReturn(List.of(seat));
        when(seatMapper.toAvailableSeatResponse(seat, "VIP", new BigDecimal("500.00"), "INR")).thenReturn(mapped);

        AvailableSeatsEnvelopeResponse response = seatAvailabilityService.getAvailability(11L);

        assertThat(response.seats()).hasSize(1);
        assertThat(response.seats().getFirst().seatId()).isEqualTo(100L);
    }

    @Test
    void should_include_expired_soft_locked_seats_in_available_response() {
        SlotSummaryDto slot = new SlotSummaryDto(12L, "ACTIVE", "eb-12", SeatingMode.RESERVED, 1L, 2L, 3L, null);
        PricingTierDto tier = tier();
        Seat seat = seat(101L, SeatLockState.SOFT_LOCKED);
        ReflectionTestUtils.setField(seat, "lockedUntil", Instant.now().minusSeconds(60));

        when(slotValidationService.requireActiveAndSynced(12L)).thenReturn(slot);
        when(cartPricingService.getSlotPricingCached(12L)).thenReturn(List.of(tier));
        when(seatRepository.findAvailableForSlot(org.mockito.ArgumentMatchers.eq(12L), org.mockito.ArgumentMatchers.any(Instant.class)))
            .thenReturn(List.of(seat));
        when(seatMapper.toAvailableSeatResponse(seat, "VIP", new BigDecimal("500.00"), "INR"))
            .thenReturn(new AvailableSeatResponse(101L, "A1", "A", "S1", 10L, "VIP",
                new Money(new BigDecimal("500.00"), "INR"), SeatLockState.SOFT_LOCKED));

        AvailableSeatsEnvelopeResponse response = seatAvailabilityService.getAvailability(12L);

        assertThat(response.seats()).hasSize(1);
        assertThat(response.seats().getFirst().lockState()).isEqualTo(SeatLockState.SOFT_LOCKED);
    }

    @Test
    void should_return_ga_tier_availability_from_redis_counter() {
        SlotSummaryDto slot = new SlotSummaryDto(13L, "ACTIVE", "eb-13", SeatingMode.GA, 1L, 2L, 3L, null);
        PricingTierDto tier = tier();

        when(slotValidationService.requireActiveAndSynced(13L)).thenReturn(slot);
        when(cartPricingService.getSlotPricingCached(13L)).thenReturn(List.of(tier));
        when(gaInventoryRedisService.getCurrent(13L, 10L)).thenReturn(42L);
        when(stringRedisTemplate.hasKey("tier:blocked:13:10")).thenReturn(false);

        AvailableSeatsEnvelopeResponse response = seatAvailabilityService.getAvailability(13L);

        assertThat(response.tiers()).hasSize(1);
        assertThat(response.tiers().getFirst().available()).isEqualTo(42L);
    }

    @Test
    void should_fallback_to_db_when_redis_counter_missing() {
        SlotSummaryDto slot = new SlotSummaryDto(14L, "ACTIVE", "eb-14", SeatingMode.GA, 1L, 2L, 3L, null);
        PricingTierDto tier = tier();

        when(slotValidationService.requireActiveAndSynced(14L)).thenReturn(slot);
        when(cartPricingService.getSlotPricingCached(14L)).thenReturn(List.of(tier));
        when(gaInventoryRedisService.getCurrent(14L, 10L)).thenReturn(null);
        when(gaInventoryClaimRepository.countByShowSlotIdAndPricingTierIdAndLockState(14L, 10L, SeatLockState.CONFIRMED)).thenReturn(8L);
        when(stringRedisTemplate.hasKey("tier:blocked:14:10")).thenReturn(false);

        AvailableSeatsEnvelopeResponse response = seatAvailabilityService.getAvailability(14L);

        assertThat(response.tiers().getFirst().available()).isEqualTo(92L);
        verify(gaInventoryClaimRepository).countByShowSlotIdAndPricingTierIdAndLockState(14L, 10L, SeatLockState.CONFIRMED);
    }

    @Test
    void should_mark_tier_blocked_when_redis_block_flag_set() {
        SlotSummaryDto slot = new SlotSummaryDto(15L, "ACTIVE", "eb-15", SeatingMode.GA, 1L, 2L, 3L, null);
        PricingTierDto tier = tier();

        when(slotValidationService.requireActiveAndSynced(15L)).thenReturn(slot);
        when(cartPricingService.getSlotPricingCached(15L)).thenReturn(List.of(tier));
        when(gaInventoryRedisService.getCurrent(15L, 10L)).thenReturn(10L);
        when(stringRedisTemplate.hasKey("tier:blocked:15:10")).thenReturn(true);

        AvailableSeatsEnvelopeResponse response = seatAvailabilityService.getAvailability(15L);

        assertThat(response.tiers().getFirst().blocked()).isTrue();
    }

    private PricingTierDto tier() {
        return new PricingTierDto(
            10L,
            "VIP",
            new Money(new BigDecimal("500.00"), "INR"),
            100,
            "PAID",
            "tc_10",
            "inv_10",
            4,
            new BigDecimal("10.00")
        );
    }

    private Seat seat(Long id, SeatLockState state) {
        Seat seat = new Seat(99L, 10L, "tc_10", "A1", "A", "S1");
        ReflectionTestUtils.setField(seat, "id", id);
        ReflectionTestUtils.setField(seat, "lockState", state);
        return seat;
    }
}
