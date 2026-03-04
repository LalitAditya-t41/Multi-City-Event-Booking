package com.eventplatform.bookinginventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventplatform.bookinginventory.domain.enums.CartStatus;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CartDomainTest {

    @Test
    void should_reject_ga_cart_item_with_non_null_seat_id() {
        assertThatThrownBy(() -> new CartItem(
            1L,
            2L,
            10L,
            "tc_10",
            new Money(new BigDecimal("500.00"), "INR"),
            1
        ))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("either seat or GA claim");
    }

    @Test
    void should_reject_reserved_cart_item_with_null_seat_id() {
        assertThatThrownBy(() -> new CartItem(
            null,
            null,
            10L,
            "tc_10",
            new Money(new BigDecimal("500.00"), "INR"),
            1
        ))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("either seat or GA claim");
    }

    @Test
    void should_reject_cart_item_with_both_seat_id_and_ga_claim_id_null() {
        assertThatThrownBy(() -> new CartItem(
            null,
            null,
            10L,
            "tc_10",
            new Money(new BigDecimal("500.00"), "INR"),
            1
        ))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("either seat or GA claim");
    }

    @Test
    void should_mark_cart_expired_when_expires_at_in_past() {
        Cart cart = new Cart(1L, 2L, 3L, SeatingMode.RESERVED, Duration.ofMinutes(5), "INR");
        cart.extendTtl(Duration.ofSeconds(1));

        assertThat(cart.isExpired(Instant.now().plusSeconds(2))).isTrue();
    }

    @Test
    void should_extend_cart_ttl_correctly() {
        Cart cart = new Cart(1L, 2L, 3L, SeatingMode.RESERVED, Duration.ofMinutes(1), "INR");
        Instant before = Instant.now();

        cart.extendTtl(Duration.ofMinutes(10));

        assertThat(cart.getExpiresAt()).isAfter(before.plus(Duration.ofMinutes(9)));
    }

    @Test
    void should_transition_cart_to_confirmed() {
        Cart cart = new Cart(1L, 2L, 3L, SeatingMode.RESERVED, Duration.ofMinutes(5), "INR");

        cart.confirm();

        assertThat(cart.getStatus()).isEqualTo(CartStatus.CONFIRMED);
    }

    @Test
    void should_transition_cart_to_abandoned() {
        Cart cart = new Cart(1L, 2L, 3L, SeatingMode.RESERVED, Duration.ofMinutes(5), "INR");

        cart.abandon();

        assertThat(cart.getStatus()).isEqualTo(CartStatus.ABANDONED);
    }
}
