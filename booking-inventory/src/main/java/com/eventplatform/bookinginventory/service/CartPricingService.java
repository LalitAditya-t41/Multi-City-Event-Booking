package com.eventplatform.bookinginventory.service;

import com.eventplatform.bookinginventory.domain.Cart;
import com.eventplatform.bookinginventory.domain.CartItem;
import com.eventplatform.bookinginventory.domain.GroupDiscountRule;
import com.eventplatform.bookinginventory.repository.GroupDiscountRuleRepository;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.dto.PricingTierDto;
import com.eventplatform.shared.common.service.SlotPricingReader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class CartPricingService {

    private static final Logger log = LoggerFactory.getLogger(CartPricingService.class);
    private static final Duration PRICING_CACHE_TTL = Duration.ofMinutes(10);

    public record CartPricingResult(
        Money subtotal,
        Money discount,
        Money total,
        Map<Long, Money> itemDiscounts
    ) {
    }

    private final GroupDiscountRuleRepository groupDiscountRuleRepository;
    private final SlotPricingReader slotPricingReader;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper redisObjectMapper;

    public CartPricingService(
        GroupDiscountRuleRepository groupDiscountRuleRepository,
        SlotPricingReader slotPricingReader,
        StringRedisTemplate stringRedisTemplate,
        @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper
    ) {
        this.groupDiscountRuleRepository = groupDiscountRuleRepository;
        this.slotPricingReader = slotPricingReader;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisObjectMapper = redisObjectMapper;
    }

    public List<PricingTierDto> getSlotPricingCached(Long slotId) {
        String key = pricingCacheKey(slotId);

        try {
            String cachedJson = stringRedisTemplate.opsForValue().get(key);
            if (cachedJson != null && !cachedJson.isBlank()) {
                return redisObjectMapper.readValue(cachedJson, new TypeReference<List<PricingTierDto>>() {
                });
            }
        } catch (JsonProcessingException ex) {
            log.warn("Failed to deserialize pricing cache. slotId={} reason={}", slotId, ex.getMessage());
        } catch (Exception ex) {
            log.warn("Failed to read pricing cache. slotId={} reason={}", slotId, ex.getMessage());
        }

        List<PricingTierDto> pricing = slotPricingReader.getSlotPricing(slotId);

        try {
            String payload = redisObjectMapper.writeValueAsString(pricing);
            stringRedisTemplate.opsForValue().set(key, payload, PRICING_CACHE_TTL);
        } catch (Exception ex) {
            log.warn("Failed to write pricing cache. slotId={} reason={}", slotId, ex.getMessage());
        }

        return pricing;
    }

    public CartPricingResult recompute(Cart cart, List<CartItem> items) {
        if (items.isEmpty()) {
            Money zero = new Money(BigDecimal.ZERO, cart.getDiscountAmount().currency());
            cart.setDiscountAmount(zero);
            return new CartPricingResult(zero, zero, zero, Map.of());
        }

        String currency = items.getFirst().getBasePrice().currency();
        BigDecimal subtotalAmount = items.stream()
            .map(this::lineAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<Long, List<CartItem>> byTier = items.stream().collect(Collectors.groupingBy(CartItem::getPricingTierId));
        Map<Long, GroupDiscountRule> rulesByTier = groupDiscountRuleRepository.findByShowSlotId(cart.getShowSlotId()).stream()
            .collect(Collectors.toMap(GroupDiscountRule::getPricingTierId, Function.identity(), (a, b) -> a));

        BigDecimal totalDiscount = BigDecimal.ZERO;
        Map<Long, Money> itemDiscounts = new HashMap<>();

        for (Map.Entry<Long, List<CartItem>> entry : byTier.entrySet()) {
            Long tierId = entry.getKey();
            List<CartItem> tierItems = entry.getValue();
            GroupDiscountRule rule = rulesByTier.get(tierId);
            if (rule == null || rule.getGroupDiscountThreshold() == null || rule.getGroupDiscountPercent() == null) {
                tierItems.forEach(item -> itemDiscounts.put(item.getId(), new Money(BigDecimal.ZERO, currency)));
                continue;
            }

            int count = tierItems.stream().mapToInt(CartItem::getQuantity).sum();
            if (count < rule.getGroupDiscountThreshold()) {
                tierItems.forEach(item -> itemDiscounts.put(item.getId(), new Money(BigDecimal.ZERO, currency)));
                continue;
            }

            BigDecimal tierSubtotal = tierItems.stream().map(this::lineAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal tierDiscount = tierSubtotal
                .multiply(rule.getGroupDiscountPercent())
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            totalDiscount = totalDiscount.add(tierDiscount);

            for (CartItem item : tierItems) {
                BigDecimal ratio = tierSubtotal.signum() == 0
                    ? BigDecimal.ZERO
                    : lineAmount(item).divide(tierSubtotal, 8, RoundingMode.HALF_UP);
                BigDecimal itemDiscount = tierDiscount.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                itemDiscounts.put(item.getId(), new Money(itemDiscount, currency));
            }
        }

        Money subtotal = new Money(subtotalAmount.setScale(2, RoundingMode.HALF_UP), currency);
        Money discount = new Money(totalDiscount.setScale(2, RoundingMode.HALF_UP), currency);
        Money total = new Money(subtotal.amount().subtract(discount.amount()).setScale(2, RoundingMode.HALF_UP), currency);
        cart.setDiscountAmount(discount);

        return new CartPricingResult(subtotal, discount, total, itemDiscounts);
    }

    private String pricingCacheKey(Long slotId) {
        return "pricing:slot:" + slotId;
    }

    private BigDecimal lineAmount(CartItem item) {
        return item.getBasePrice().amount().multiply(BigDecimal.valueOf(item.getQuantity()));
    }
}
