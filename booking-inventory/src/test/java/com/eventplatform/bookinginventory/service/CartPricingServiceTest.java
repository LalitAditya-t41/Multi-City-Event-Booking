package com.eventplatform.bookinginventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.bookinginventory.repository.GroupDiscountRuleRepository;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.dto.PricingTierDto;
import com.eventplatform.shared.common.service.SlotPricingReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class CartPricingServiceTest {

    @Mock
    private GroupDiscountRuleRepository groupDiscountRuleRepository;

    @Mock
    private SlotPricingReader slotPricingReader;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CartPricingService cartPricingService;

    @BeforeEach
    void setUp() {
        cartPricingService = new CartPricingService(
            groupDiscountRuleRepository,
            slotPricingReader,
            stringRedisTemplate,
            new ObjectMapper()
        );
    }

    @Test
    void should_return_cached_result_on_redis_hit_without_reader_call() throws Exception {
        List<PricingTierDto> expected = List.of(sampleTier());
        String json = new ObjectMapper().writeValueAsString(expected);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("pricing:slot:10")).thenReturn(json);

        List<PricingTierDto> result = cartPricingService.getSlotPricingCached(10L);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().tierId()).isEqualTo(1L);
        verify(slotPricingReader, never()).getSlotPricing(any());
    }

    @Test
    void should_call_reader_and_cache_when_redis_miss() {
        List<PricingTierDto> expected = List.of(sampleTier());

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("pricing:slot:11")).thenReturn(null);
        when(slotPricingReader.getSlotPricing(11L)).thenReturn(expected);

        List<PricingTierDto> result = cartPricingService.getSlotPricingCached(11L);

        assertThat(result).isEqualTo(expected);
        verify(slotPricingReader).getSlotPricing(11L);
        verify(valueOperations).set(eq("pricing:slot:11"), any(String.class), eq(Duration.ofMinutes(10)));
    }

    @Test
    void should_fallback_to_reader_when_redis_read_fails() {
        List<PricingTierDto> expected = List.of(sampleTier());

        when(stringRedisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        when(slotPricingReader.getSlotPricing(12L)).thenReturn(expected);

        List<PricingTierDto> result = cartPricingService.getSlotPricingCached(12L);

        assertThat(result).isEqualTo(expected);
        verify(slotPricingReader).getSlotPricing(12L);
    }

    private PricingTierDto sampleTier() {
        return new PricingTierDto(
            1L,
            "Standard",
            new Money(new BigDecimal("500.00"), "INR"),
            100,
            "PAID",
            "tc_1",
            "inv_1",
            4,
            new BigDecimal("10.00")
        );
    }
}
