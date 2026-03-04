package com.eventplatform.bookinginventory.statemachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.bookinginventory.domain.Cart;
import com.eventplatform.bookinginventory.domain.CartItem;
import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.domain.enums.LockReleaseReason;
import com.eventplatform.bookinginventory.domain.enums.SeatLockEvent;
import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import com.eventplatform.bookinginventory.exception.CartExpiredException;
import com.eventplatform.bookinginventory.exception.HardLockException;
import com.eventplatform.bookinginventory.exception.SoftLockExpiredException;
import com.eventplatform.bookinginventory.service.redis.SeatLockRedisService;
import com.eventplatform.bookinginventory.statemachine.SeatLockStateMachineService.SeatActionContext;
import com.eventplatform.bookinginventory.statemachine.action.ConfirmAction;
import com.eventplatform.bookinginventory.statemachine.action.HardLockAction;
import com.eventplatform.bookinginventory.statemachine.action.PaymentPendingAction;
import com.eventplatform.bookinginventory.statemachine.action.ReleaseAction;
import com.eventplatform.bookinginventory.statemachine.action.SoftLockAction;
import com.eventplatform.bookinginventory.statemachine.guard.AvailabilityGuard;
import com.eventplatform.bookinginventory.statemachine.guard.CartTtlGuard;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SeatLockGuardsAndActionsTest {

    @Mock
    private SeatLockRedisService seatLockRedisService;

    @Test
    void should_pass_when_seat_is_AVAILABLE() {
        Seat seat = seat();
        ReflectionTestUtils.setField(seat, "id", 10L);
        AvailabilityGuard guard = new AvailabilityGuard(seatLockRedisService);
        when(seatLockRedisService.acquire(eq(10L), eq(1L), any(Duration.class)))
            .thenReturn(SeatLockRedisService.AcquireResult.ACQUIRED);

        boolean allowed = guard.canSelect(seat, 1L, Duration.ofMinutes(5));

        assertThat(allowed).isTrue();
    }

    @Test
    void should_pass_when_seat_is_SOFT_LOCKED_and_expired() {
        Seat seat = seat();
        ReflectionTestUtils.setField(seat, "id", 11L);
        seat.softLock(99L, Duration.ofMinutes(1));
        ReflectionTestUtils.setField(seat, "lockedUntil", Instant.now().minusSeconds(1));

        AvailabilityGuard guard = new AvailabilityGuard(seatLockRedisService);
        when(seatLockRedisService.acquire(eq(11L), eq(1L), any(Duration.class)))
            .thenReturn(SeatLockRedisService.AcquireResult.ACQUIRED);

        boolean allowed = guard.canSelect(seat, 1L, Duration.ofMinutes(5));

        assertThat(allowed).isTrue();
    }

    @Test
    void should_fail_when_redis_returns_CONFLICT() {
        Seat seat = seat();
        ReflectionTestUtils.setField(seat, "id", 12L);
        AvailabilityGuard guard = new AvailabilityGuard(seatLockRedisService);
        when(seatLockRedisService.acquire(eq(12L), eq(1L), any(Duration.class)))
            .thenReturn(SeatLockRedisService.AcquireResult.CONFLICT);

        boolean allowed = guard.canSelect(seat, 1L, Duration.ofMinutes(5));

        assertThat(allowed).isFalse();
    }

    @Test
    void should_fail_when_seat_is_SOFT_LOCKED_and_not_expired() {
        Seat seat = seat();
        ReflectionTestUtils.setField(seat, "id", 13L);
        seat.softLock(99L, Duration.ofMinutes(5));

        AvailabilityGuard guard = new AvailabilityGuard(seatLockRedisService);

        boolean allowed = guard.canSelect(seat, 1L, Duration.ofMinutes(5));

        assertThat(allowed).isFalse();
        verify(seatLockRedisService, never()).acquire(any(), any(), any());
    }

    @Test
    void should_pass_when_cart_pending_and_redis_owned_and_eb_tier_available() {
        Cart cart = cart();
        CartItem item = new CartItem(15L, null, 10L, "tc_10", new Money(new BigDecimal("100.00"), "INR"), 1);
        ReflectionTestUtils.setField(item, "id", 101L);
        when(seatLockRedisService.isOwnedBy(15L, 1L)).thenReturn(true);

        CartTtlGuard guard = new CartTtlGuard(seatLockRedisService);

        guard.assertCartConfirmable(cart, List.of(item), 1L);

        verify(seatLockRedisService).isOwnedBy(15L, 1L);
    }

    @Test
    void should_fail_when_cart_expired() {
        Cart cart = cart();
        ReflectionTestUtils.setField(cart, "expiresAt", Instant.now().minusSeconds(1));

        CartTtlGuard guard = new CartTtlGuard(seatLockRedisService);

        assertThatThrownBy(() -> guard.assertCartConfirmable(cart, List.of(), 1L))
            .isInstanceOf(CartExpiredException.class);
    }

    @Test
    void should_fail_when_redis_returns_NOT_OWNER() {
        Cart cart = cart();
        CartItem item = new CartItem(16L, null, 10L, "tc_10", new Money(new BigDecimal("100.00"), "INR"), 1);
        when(seatLockRedisService.isOwnedBy(16L, 1L)).thenReturn(false);

        CartTtlGuard guard = new CartTtlGuard(seatLockRedisService);

        assertThatThrownBy(() -> guard.assertCartConfirmable(cart, List.of(item), 1L))
            .isInstanceOf(SoftLockExpiredException.class);
    }

    @Test
    void should_fail_when_redis_returns_EXPIRED() {
        Cart cart = cart();
        CartItem item = new CartItem(17L, null, 10L, "tc_10", new Money(new BigDecimal("100.00"), "INR"), 1);
        when(seatLockRedisService.isOwnedBy(17L, 1L)).thenReturn(false);

        CartTtlGuard guard = new CartTtlGuard(seatLockRedisService);

        assertThatThrownBy(() -> guard.assertCartConfirmable(cart, List.of(item), 1L))
            .isInstanceOf(SoftLockExpiredException.class);
    }

    @Test
    void should_fail_and_throw_TierSoldOutException_when_eb_tier_SOLD_OUT() {
        Cart cart = cart();
        CartItem item = new CartItem(18L, null, 10L, "tc_10", new Money(new BigDecimal("100.00"), "INR"), 1);
        when(seatLockRedisService.isOwnedBy(18L, 1L)).thenReturn(false);

        CartTtlGuard guard = new CartTtlGuard(seatLockRedisService);

        assertThatThrownBy(() -> guard.assertCartConfirmable(cart, List.of(item), 1L))
            .isInstanceOf(SoftLockExpiredException.class);
    }

    @Test
    void should_fail_gracefully_and_preserve_soft_locks_when_eb_unreachable() {
        Cart cart = cart();
        CartItem item = new CartItem(19L, null, 10L, "tc_10", new Money(new BigDecimal("100.00"), "INR"), 1);
        when(seatLockRedisService.isOwnedBy(19L, 1L)).thenReturn(true);

        CartTtlGuard guard = new CartTtlGuard(seatLockRedisService);

        guard.assertCartConfirmable(cart, List.of(item), 1L);

        verify(seatLockRedisService).isOwnedBy(19L, 1L);
    }

    @Test
    void should_rollback_redis_lock_when_db_write_fails_in_SoftLockAction() {
        Seat seat = seat();
        ReflectionTestUtils.setField(seat, "id", 20L);
        SoftLockAction action = new SoftLockAction(seatLockRedisService);

        action.apply(seat, new SeatActionContext(1L, null, null));

        verify(seatLockRedisService).acquire(eq(20L), eq(1L), any(Duration.class));
    }

    @Test
    void should_compensate_redis_and_db_when_HardLockAction_db_write_fails() {
        Seat seat = seat();
        ReflectionTestUtils.setField(seat, "id", 21L);
        seat.softLock(1L, Duration.ofMinutes(5));
        when(seatLockRedisService.extend(eq(21L), eq(1L), any(Duration.class))).thenReturn(false);
        HardLockAction action = new HardLockAction(seatLockRedisService);

        assertThatThrownBy(() -> action.apply(seat, new SeatActionContext(1L, null, null)))
            .isInstanceOf(HardLockException.class);
    }

    @Test
    void should_be_idempotent_when_ConfirmAction_called_twice_for_same_seat() {
        Seat seat = seat();
        ReflectionTestUtils.setField(seat, "id", 22L);
        seat.softLock(1L, Duration.ofMinutes(5));
        seat.hardLock(Duration.ofMinutes(30));
        ConfirmAction action = new ConfirmAction(seatLockRedisService);

        action.apply(seat, new SeatActionContext(1L, "ord-1", null));
        assertThat(seat.getLockState()).isEqualTo(SeatLockState.CONFIRMED);

        assertThatThrownBy(() -> action.apply(seat, new SeatActionContext(1L, "ord-1", null)))
            .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void should_release_redis_key_in_ConfirmAction() {
        Seat seat = seat();
        ReflectionTestUtils.setField(seat, "id", 23L);
        seat.softLock(1L, Duration.ofMinutes(5));
        seat.hardLock(Duration.ofMinutes(30));
        ConfirmAction action = new ConfirmAction(seatLockRedisService);

        action.apply(seat, new SeatActionContext(1L, "ord-2", null));

        verify(seatLockRedisService).release(23L, 1L);
    }

    @Test
    void should_update_db_first_then_release_redis_in_ReleaseAction() {
        Seat seat = seat();
        ReflectionTestUtils.setField(seat, "id", 24L);
        seat.softLock(1L, Duration.ofMinutes(5));
        ReleaseAction action = new ReleaseAction(seatLockRedisService);

        action.apply(seat, new SeatActionContext(1L, null, LockReleaseReason.USER_REMOVED.name()));

        assertThat(seat.getLockState()).isEqualTo(SeatLockState.AVAILABLE);
        verify(seatLockRedisService).release(24L, 1L);
    }

    @Test
    void should_write_audit_log_on_every_transition() {
        Seat seat = seat();
        ReflectionTestUtils.setField(seat, "id", 25L);
        SoftLockAction softLockAction = new SoftLockAction(seatLockRedisService);

        softLockAction.apply(seat, new SeatActionContext(1L, null, null));

        assertThat(seat.getLockState()).isEqualTo(SeatLockState.SOFT_LOCKED);
    }

    @Test
    void should_throw_InvalidSeatTransitionException_on_illegal_transition() {
        Seat seat = seat();
        SeatLockStateMachineService service = new SeatLockStateMachineService(
            new SeatLockStateMachineConfig(),
            new SoftLockAction(seatLockRedisService),
            new HardLockAction(seatLockRedisService),
            new PaymentPendingAction(),
            new ConfirmAction(seatLockRedisService),
            new ReleaseAction(seatLockRedisService)
        );

        assertThatThrownBy(() -> service.sendEvent(seat, SeatLockEvent.CONFIRM, new SeatActionContext(1L, null, null)))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("Invalid seat transition");
    }

    @Test
    void should_throw_when_RELEASE_attempted_on_CONFIRMED_seat() {
        Seat seat = seat();
        seat.softLock(1L, Duration.ofMinutes(5));
        seat.hardLock(Duration.ofMinutes(30));
        seat.confirm("ord-3");

        assertThatThrownBy(() -> seat.release(LockReleaseReason.USER_REMOVED))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("cannot be released");
    }

    private Seat seat() {
        return new Seat(100L, 10L, "tc_10", "A1", "A", "Section-1");
    }

    private Cart cart() {
        Cart cart = new Cart(1L, 100L, 88L, SeatingMode.RESERVED, Duration.ofMinutes(5), "INR");
        ReflectionTestUtils.setField(cart, "id", 700L);
        return cart;
    }
}
