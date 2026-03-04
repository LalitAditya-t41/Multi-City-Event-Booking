package com.eventplatform.bookinginventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.bookinginventory.api.dto.request.AddSeatRequest;
import com.eventplatform.bookinginventory.api.dto.request.ConfirmCartRequest;
import com.eventplatform.bookinginventory.api.dto.response.CartResponse;
import com.eventplatform.bookinginventory.domain.Cart;
import com.eventplatform.bookinginventory.domain.CartItem;
import com.eventplatform.bookinginventory.domain.GaInventoryClaim;
import com.eventplatform.bookinginventory.domain.enums.CartStatus;
import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import com.eventplatform.bookinginventory.exception.SeatUnavailableException;
import com.eventplatform.bookinginventory.mapper.CartMapper;
import com.eventplatform.bookinginventory.repository.CartItemRepository;
import com.eventplatform.bookinginventory.repository.CartRepository;
import com.eventplatform.bookinginventory.repository.GaInventoryClaimRepository;
import com.eventplatform.bookinginventory.repository.SeatLockAuditLogRepository;
import com.eventplatform.bookinginventory.repository.SeatRepository;
import com.eventplatform.bookinginventory.service.CartPricingService.CartPricingResult;
import com.eventplatform.bookinginventory.service.redis.GaInventoryRedisService;
import com.eventplatform.bookinginventory.service.redis.SeatLockRedisService;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.dto.PricingTierDto;
import com.eventplatform.shared.common.dto.SlotSummaryDto;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.common.event.published.CartAssembledEvent;
import com.eventplatform.shared.common.exception.BaseException;
import com.eventplatform.shared.eventbrite.service.EbTicketService;
import com.eventplatform.shared.eventbrite.service.EbTokenStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;
    @Mock
    private CartItemRepository cartItemRepository;
    @Mock
    private SeatRepository seatRepository;
    @Mock
    private GaInventoryClaimRepository gaInventoryClaimRepository;
    @Mock
    private SeatLockAuditLogRepository seatLockAuditLogRepository;
    @Mock
    private SlotValidationService slotValidationService;
    @Mock
    private SeatLockRedisService seatLockRedisService;
    @Mock
    private GaInventoryRedisService gaInventoryRedisService;
    @Mock
    private CartPricingService cartPricingService;
    @Mock
    private ConflictResolutionService conflictResolutionService;
    @Mock
    private CartMapper cartMapper;
    @Mock
    private EbTicketService ebTicketService;
    @Mock
    private EbTokenStore ebTokenStore;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private CartService cartService;

    @Test
    void should_create_new_cart_on_first_add_seat_for_user_slot() {
        SlotSummaryDto slot = new SlotSummaryDto(50L, "ACTIVE", "eb-50", SeatingMode.GA, 44L, 2L, 3L, null);
        PricingTierDto tier = tier();
        Cart cart = cart(1L, 50L, 44L, SeatingMode.GA);

        setupSuccessfulGaAdd(slot, tier, cart);
        when(cartRepository.findByUserIdAndShowSlotIdAndStatus(1L, 50L, CartStatus.PENDING)).thenReturn(Optional.empty());

        cartService.addSeat(1L, 44L, "USER", new AddSeatRequest(50L, null, 1L, 2));

        verify(cartRepository, atLeastOnce()).save(any(Cart.class));
    }

    @Test
    void should_upsert_existing_pending_cart_on_repeat_add_seat() {
        SlotSummaryDto slot = new SlotSummaryDto(51L, "ACTIVE", "eb-51", SeatingMode.GA, 44L, 2L, 3L, null);
        PricingTierDto tier = tier();
        Cart cart = cart(1L, 51L, 44L, SeatingMode.GA);

        setupSuccessfulGaAdd(slot, tier, cart);
        when(cartRepository.findByUserIdAndShowSlotIdAndStatus(1L, 51L, CartStatus.PENDING)).thenReturn(Optional.of(cart));

        cartService.addSeat(1L, 44L, "USER", new AddSeatRequest(51L, null, 1L, 2));

        verify(cartRepository, atLeastOnce()).save(any(Cart.class));
    }

    @Test
    void should_reject_add_seat_when_slot_not_ACTIVE() {
        when(slotValidationService.requireActiveAndSynced(52L)).thenThrow(new BaseException(
            "Slot is not active",
            "SLOT_NOT_ACTIVE",
            HttpStatus.CONFLICT
        ) {
        });

        assertThatThrownBy(() -> cartService.addSeat(1L, 44L, "USER", new AddSeatRequest(52L, null, 1L, 1)))
            .isInstanceOf(BaseException.class)
            .hasMessageContaining("Slot is not active");
    }

    @Test
    void should_reject_add_seat_when_eb_event_id_null() {
        when(slotValidationService.requireActiveAndSynced(53L)).thenThrow(new BaseException(
            "Eventbrite event id is missing for slot",
            "EB_EVENT_NOT_SYNCED",
            HttpStatus.SERVICE_UNAVAILABLE
        ) {
        });

        assertThatThrownBy(() -> cartService.addSeat(1L, 44L, "USER", new AddSeatRequest(53L, null, 1L, 1)))
            .isInstanceOf(BaseException.class)
            .hasMessageContaining("Eventbrite event id is missing");
    }

    @Test
    void should_return_409_with_alternatives_when_seat_unavailable() {
        SlotSummaryDto slot = new SlotSummaryDto(54L, "ACTIVE", "eb-54", SeatingMode.RESERVED, 44L, 2L, 3L, null);
        PricingTierDto tier = tier();
        Cart cart = cart(1L, 54L, 44L, SeatingMode.RESERVED);
        com.eventplatform.bookinginventory.domain.Seat seat = new com.eventplatform.bookinginventory.domain.Seat(54L, 1L, "tc_1", "A1", "A", "S1");
        ReflectionTestUtils.setField(seat, "id", 200L);

        when(slotValidationService.requireActiveAndSynced(54L)).thenReturn(slot);
        when(cartPricingService.getSlotPricingCached(54L)).thenReturn(List.of(tier));
        when(cartRepository.findByUserIdAndShowSlotIdAndStatus(1L, 54L, CartStatus.PENDING)).thenReturn(Optional.of(cart));
        when(seatRepository.findByIdWithLock(200L)).thenReturn(Optional.of(seat));
        when(seatLockRedisService.acquire(eq(200L), eq(1L), any(Duration.class))).thenReturn(SeatLockRedisService.AcquireResult.CONFLICT);
        when(conflictResolutionService.alternativesFor(seat)).thenReturn(
            new com.eventplatform.bookinginventory.api.dto.response.SeatAlternativesResponse(200L, List.of(), List.of())
        );
        when(ebTokenStore.getAccessToken(44L)).thenReturn("token");

        assertThatThrownBy(() -> cartService.addSeat(1L, 44L, "USER", new AddSeatRequest(54L, 200L, 1L, 1)))
            .isInstanceOf(SeatUnavailableException.class);
    }

    @Test
    void should_recompute_full_cart_total_on_every_add_and_remove() {
        SlotSummaryDto slot = new SlotSummaryDto(55L, "ACTIVE", "eb-55", SeatingMode.GA, 44L, 2L, 3L, null);
        PricingTierDto tier = tier();
        Cart cart = cart(1L, 55L, 44L, SeatingMode.GA);

        setupSuccessfulGaAdd(slot, tier, cart);
        when(cartRepository.findByUserIdAndShowSlotIdAndStatus(1L, 55L, CartStatus.PENDING)).thenReturn(Optional.of(cart));

        cartService.addSeat(1L, 44L, "USER", new AddSeatRequest(55L, null, 1L, 2));

        CartItem item = new CartItem(null, 601L, 1L, "tc_1", new Money(new BigDecimal("500.00"), "INR"), 1);
        ReflectionTestUtils.setField(item, "id", 701L);
        ReflectionTestUtils.setField(item, "cart", cart);
        when(cartItemRepository.findByIdAndCartUserId(701L, 1L)).thenReturn(Optional.of(item));
        when(gaInventoryClaimRepository.findById(601L)).thenReturn(Optional.of(new GaInventoryClaim(55L, 1L, 1L, 900L, 1, Instant.now().plusSeconds(30))));

        cartService.removeItem(1L, 701L);

        verify(cartPricingService, times(2)).recompute(eq(cart), any(List.class));
    }

    @Test
    void should_publish_CartAssembledEvent_with_orgId_and_ebEventId_on_confirm() {
        Cart cart = cart(1L, 56L, 44L, SeatingMode.GA);
        when(cartRepository.findById(900L)).thenReturn(Optional.of(cart));
        when(cartPricingService.getSlotPricingCached(56L)).thenReturn(List.of(tier()));
        when(slotValidationService.requireActiveAndSynced(56L))
            .thenReturn(new SlotSummaryDto(56L, "ACTIVE", "eb-56", SeatingMode.GA, 44L, 2L, 3L, null));
        when(cartItemRepository.findByCartId(900L)).thenReturn(List.of());
        when(cartPricingService.recompute(eq(cart), any(List.class))).thenReturn(new CartPricingResult(
            new Money(new BigDecimal("0.00"), "INR"),
            new Money(new BigDecimal("0.00"), "INR"),
            new Money(new BigDecimal("0.00"), "INR"),
            Map.of()
        ));
        when(cartRepository.save(cart)).thenReturn(cart);
        when(cartMapper.toResponse(eq(cart), any(List.class), any(CartPricingResult.class), any(Map.class), any(Map.class)))
            .thenReturn(new CartResponse(900L, 56L, CartStatus.PENDING, cart.getExpiresAt(), SeatingMode.GA, List.of(),
                new Money(new BigDecimal("0.00"), "INR"), new Money(new BigDecimal("0.00"), "INR"), new Money(new BigDecimal("0.00"), "INR")));

        cartService.confirm(1L, 44L, "test@example.com", new ConfirmCartRequest(900L));

        ArgumentCaptor<CartAssembledEvent> captor = ArgumentCaptor.forClass(CartAssembledEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().orgId()).isEqualTo(44L);
        assertThat(captor.getValue().ebEventId()).isEqualTo("eb-56");
    }

    @Test
    void should_not_publish_CartAssembledEvent_when_confirm_fails() {
        Cart cart = cart(1L, 57L, 44L, SeatingMode.RESERVED);
        ReflectionTestUtils.setField(cart, "expiresAt", Instant.now().minusSeconds(1));
        when(cartRepository.findById(901L)).thenReturn(Optional.of(cart));

        assertThatThrownBy(() -> cartService.confirm(1L, 44L, "test@example.com", new ConfirmCartRequest(901L)))
            .isInstanceOf(BaseException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_extend_cart_expires_at_to_30_min_on_hard_lock() {
        Cart cart = cart(1L, 58L, 44L, SeatingMode.GA);
        Instant before = cart.getExpiresAt();
        when(cartRepository.findById(902L)).thenReturn(Optional.of(cart));
        when(cartPricingService.getSlotPricingCached(58L)).thenReturn(List.of(tier()));
        when(slotValidationService.requireActiveAndSynced(58L))
            .thenReturn(new SlotSummaryDto(58L, "ACTIVE", "eb-58", SeatingMode.GA, 44L, 2L, 3L, null));
        when(cartItemRepository.findByCartId(900L)).thenReturn(List.of());
        when(cartPricingService.recompute(eq(cart), any(List.class))).thenReturn(new CartPricingResult(
            new Money(new BigDecimal("0.00"), "INR"),
            new Money(new BigDecimal("0.00"), "INR"),
            new Money(new BigDecimal("0.00"), "INR"),
            Map.of()
        ));
        when(cartMapper.toResponse(eq(cart), any(List.class), any(CartPricingResult.class), any(Map.class), any(Map.class)))
            .thenReturn(new CartResponse(902L, 58L, CartStatus.PENDING, cart.getExpiresAt(), SeatingMode.GA, List.of(),
                new Money(new BigDecimal("0.00"), "INR"), new Money(new BigDecimal("0.00"), "INR"), new Money(new BigDecimal("0.00"), "INR")));

        cartService.confirm(1L, 44L, "test@example.com", new ConfirmCartRequest(902L));

        assertThat(cart.getExpiresAt()).isAfter(before.plus(Duration.ofMinutes(20)));
    }

    private void setupSuccessfulGaAdd(SlotSummaryDto slot, PricingTierDto tier, Cart cart) {
        when(slotValidationService.requireActiveAndSynced(slot.slotId())).thenReturn(slot);
        when(cartPricingService.getSlotPricingCached(slot.slotId())).thenReturn(List.of(tier));
        when(ebTokenStore.getAccessToken(44L)).thenReturn("token");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("tier:blocked:" + slot.slotId() + ":1")).thenReturn(null);
        when(gaInventoryRedisService.claim(slot.slotId(), 1L, 2)).thenReturn(5L);

        GaInventoryClaim claim = new GaInventoryClaim(slot.slotId(), 1L, 1L, 900L, 2, Instant.now().plusSeconds(30));
        ReflectionTestUtils.setField(claim, "id", 601L);
        when(gaInventoryClaimRepository.save(any(GaInventoryClaim.class))).thenReturn(claim);

        ReflectionTestUtils.setField(cart, "id", 900L);
        when(cartRepository.save(any(Cart.class))).thenReturn(cart);

        CartItem responseItem = new CartItem(null, 601L, 1L, "tc_1", new Money(new BigDecimal("500.00"), "INR"), 2);
        ReflectionTestUtils.setField(responseItem, "id", 701L);
        ReflectionTestUtils.setField(responseItem, "cart", cart);
        when(cartItemRepository.findByCartId(900L)).thenReturn(List.of(responseItem));
        when(cartPricingService.recompute(eq(cart), any(List.class))).thenReturn(new CartPricingResult(
            new Money(new BigDecimal("1000.00"), "INR"),
            new Money(new BigDecimal("100.00"), "INR"),
            new Money(new BigDecimal("900.00"), "INR"),
            Map.of(701L, new Money(new BigDecimal("100.00"), "INR"))
        ));
        when(cartMapper.toResponse(eq(cart), any(List.class), any(CartPricingResult.class), any(Map.class), any(Map.class)))
            .thenReturn(new CartResponse(900L, slot.slotId(), CartStatus.PENDING, cart.getExpiresAt(), slot.seatingMode(), List.of(),
                new Money(new BigDecimal("1000.00"), "INR"), new Money(new BigDecimal("100.00"), "INR"), new Money(new BigDecimal("900.00"), "INR")));
    }

    private Cart cart(Long userId, Long slotId, Long orgId, SeatingMode mode) {
        Cart cart = new Cart(userId, slotId, orgId, mode, Duration.ofMinutes(5), "INR");
        ReflectionTestUtils.setField(cart, "id", 900L);
        return cart;
    }

    private PricingTierDto tier() {
        return new PricingTierDto(
            1L,
            "VIP",
            new Money(new BigDecimal("500.00"), "INR"),
            100,
            "PAID",
            "tc_1",
            "inv_1",
            2,
            new BigDecimal("10.00")
        );
    }
}
