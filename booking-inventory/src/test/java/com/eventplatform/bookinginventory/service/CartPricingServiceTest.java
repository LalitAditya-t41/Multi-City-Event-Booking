package com.eventplatform.bookinginventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.bookinginventory.domain.Cart;
import com.eventplatform.bookinginventory.domain.CartItem;
import com.eventplatform.bookinginventory.domain.GroupDiscountRule;
import com.eventplatform.bookinginventory.service.CartPricingService.CartPricingResult;
import com.eventplatform.bookinginventory.repository.GroupDiscountRuleRepository;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.enums.SeatingMode;
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
import org.springframework.test.util.ReflectionTestUtils;

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
    void getSlotPricingCached_should_return_cached_list_when_redis_hit_and_not_call_reader() throws Exception {
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
    void getSlotPricingCached_should_call_reader_and_write_cache_on_redis_miss() {
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
    void getSlotPricingCached_should_fallback_to_reader_when_redis_read_throws() {
        List<PricingTierDto> expected = List.of(sampleTier());

        when(stringRedisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        when(slotPricingReader.getSlotPricing(12L)).thenReturn(expected);

        List<PricingTierDto> result = cartPricingService.getSlotPricingCached(12L);

        assertThat(result).isEqualTo(expected);
        verify(slotPricingReader).getSlotPricing(12L);
    }

    @Test
    void getSlotPricingCached_should_not_propagate_exception_when_redis_write_fails() {
        List<PricingTierDto> expected = List.of(sampleTier());

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("pricing:slot:13")).thenReturn(null);
        when(slotPricingReader.getSlotPricing(13L)).thenReturn(expected);
        doThrow(new RedisConnectionFailureException("write down"))
            .when(valueOperations).set(eq("pricing:slot:13"), any(String.class), eq(Duration.ofMinutes(10)));

        List<PricingTierDto> result = cartPricingService.getSlotPricingCached(13L);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void getSlotPricingCached_should_fallback_to_reader_when_cached_json_is_corrupt() {
        List<PricingTierDto> expected = List.of(sampleTier());

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("pricing:slot:14")).thenReturn("{bad-json");
        when(slotPricingReader.getSlotPricing(14L)).thenReturn(expected);

        List<PricingTierDto> result = cartPricingService.getSlotPricingCached(14L);

        assertThat(result).isEqualTo(expected);
        verify(slotPricingReader).getSlotPricing(14L);
    }

    @Test
    void should_apply_group_discount_to_all_items_when_threshold_crossed() {
        Cart cart = cart();
        CartItem one = item(101L, 10L, "500.00");
        CartItem two = item(102L, 10L, "500.00");
        when(groupDiscountRuleRepository.findByShowSlotId(99L))
            .thenReturn(List.of(new GroupDiscountRule(99L, 10L, 2, new BigDecimal("10.00"))));

        CartPricingResult result = cartPricingService.recompute(cart, List.of(one, two));

        assertThat(result.discount().amount()).isEqualByComparingTo("100.00");
        assertThat(result.total().amount()).isEqualByComparingTo("900.00");
    }

    @Test
    void should_remove_group_discount_when_item_removed_drops_tier_below_threshold() {
        Cart cart = cart();
        CartItem one = item(101L, 10L, "500.00");
        CartItem two = item(102L, 10L, "500.00");
        when(groupDiscountRuleRepository.findByShowSlotId(99L))
            .thenReturn(List.of(new GroupDiscountRule(99L, 10L, 2, new BigDecimal("10.00"))));
        cartPricingService.recompute(cart, List.of(one, two));

        CartPricingResult result = cartPricingService.recompute(cart, List.of(one));

        assertThat(result.discount().amount()).isEqualByComparingTo("0.00");
        assertThat(result.total().amount()).isEqualByComparingTo("500.00");
    }

    @Test
    void should_not_apply_group_discount_when_threshold_not_met() {
        Cart cart = cart();
        CartItem one = item(101L, 10L, "500.00");
        when(groupDiscountRuleRepository.findByShowSlotId(99L))
            .thenReturn(List.of(new GroupDiscountRule(99L, 10L, 3, new BigDecimal("10.00"))));

        CartPricingResult result = cartPricingService.recompute(cart, List.of(one));

        assertThat(result.discount().amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void should_compute_cart_total_correctly_with_mixed_tier_discounts() {
        Cart cart = cart();
        CartItem t1a = item(101L, 10L, "500.00");
        CartItem t1b = item(102L, 10L, "500.00");
        CartItem t2 = item(103L, 20L, "200.00");
        when(groupDiscountRuleRepository.findByShowSlotId(99L))
            .thenReturn(List.of(new GroupDiscountRule(99L, 10L, 2, new BigDecimal("10.00"))));

        CartPricingResult result = cartPricingService.recompute(cart, List.of(t1a, t1b, t2));

        assertThat(result.subtotal().amount()).isEqualByComparingTo("1200.00");
        assertThat(result.discount().amount()).isEqualByComparingTo("100.00");
        assertThat(result.total().amount()).isEqualByComparingTo("1100.00");
    }

    @Test
    void should_always_recompute_discount_from_scratch_never_incrementally() {
        Cart cart = cart();
        CartItem t1a = item(101L, 10L, "500.00");
        CartItem t1b = item(102L, 10L, "500.00");
        when(groupDiscountRuleRepository.findByShowSlotId(99L))
            .thenReturn(List.of(new GroupDiscountRule(99L, 10L, 2, new BigDecimal("10.00"))));

        CartPricingResult first = cartPricingService.recompute(cart, List.of(t1a, t1b));
        CartPricingResult second = cartPricingService.recompute(cart, List.of(t1a, t1b));

        assertThat(first.discount().amount()).isEqualByComparingTo(second.discount().amount());
        assertThat(second.total().amount()).isEqualByComparingTo("900.00");
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

    private Cart cart() {
        Cart cart = new Cart(1L, 99L, 77L, SeatingMode.RESERVED, Duration.ofMinutes(5), "INR");
        ReflectionTestUtils.setField(cart, "id", 501L);
        return cart;
    }

    private CartItem item(Long itemId, Long tierId, String price) {
        CartItem item = new CartItem(
            itemId,
            null,
            tierId,
            "tc_" + tierId,
            new Money(new BigDecimal(price), "INR"),
            1
        );
        ReflectionTestUtils.setField(item, "id", itemId);
        return item;
    }
}
