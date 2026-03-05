package com.eventplatform.bookinginventory.service;

import com.eventplatform.bookinginventory.api.dto.request.AbandonCartRequest;
import com.eventplatform.bookinginventory.api.dto.request.AddSeatRequest;
import com.eventplatform.bookinginventory.api.dto.request.ConfirmCartRequest;
import com.eventplatform.bookinginventory.api.dto.response.CartResponse;
import com.eventplatform.bookinginventory.domain.Cart;
import com.eventplatform.bookinginventory.domain.CartItem;
import com.eventplatform.bookinginventory.domain.GaInventoryClaim;
import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.domain.SeatLockAuditLog;
import com.eventplatform.bookinginventory.domain.enums.CartStatus;
import com.eventplatform.bookinginventory.domain.enums.LockReleaseReason;
import com.eventplatform.bookinginventory.domain.enums.SeatLockEvent;
import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import com.eventplatform.bookinginventory.exception.CartExpiredException;
import com.eventplatform.bookinginventory.exception.CartItemNotFoundException;
import com.eventplatform.bookinginventory.exception.CartNotFoundException;
import com.eventplatform.bookinginventory.exception.HardLockException;
import com.eventplatform.bookinginventory.exception.SeatLockException;
import com.eventplatform.bookinginventory.exception.SeatUnavailableException;
import com.eventplatform.bookinginventory.exception.SoftLockExpiredException;
import com.eventplatform.bookinginventory.exception.TierBlockedException;
import com.eventplatform.bookinginventory.exception.TierSoldOutException;
import com.eventplatform.bookinginventory.mapper.CartMapper;
import com.eventplatform.bookinginventory.repository.CartItemRepository;
import com.eventplatform.bookinginventory.repository.CartRepository;
import com.eventplatform.bookinginventory.repository.GaInventoryClaimRepository;
import com.eventplatform.bookinginventory.repository.SeatLockAuditLogRepository;
import com.eventplatform.bookinginventory.repository.SeatRepository;
import com.eventplatform.bookinginventory.service.CartPricingService.CartPricingResult;
import com.eventplatform.bookinginventory.service.redis.GaInventoryRedisService;
import com.eventplatform.bookinginventory.service.redis.SeatLockRedisService;
import com.eventplatform.bookinginventory.service.redis.SeatLockRedisService.AcquireResult;
import com.eventplatform.shared.common.dto.PricingTierDto;
import com.eventplatform.shared.common.dto.SlotSummaryDto;
import com.eventplatform.shared.common.event.published.CartAssembledEvent;
import com.eventplatform.shared.common.event.published.PaymentTimeoutEvent;
import com.eventplatform.shared.common.exception.BaseException;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import com.eventplatform.shared.eventbrite.dto.response.EbTicketClassResponse;
import com.eventplatform.shared.eventbrite.exception.EbAuthException;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.eventplatform.shared.eventbrite.service.EbTicketService;
import com.eventplatform.shared.eventbrite.service.EbTokenStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {

  private static final Duration SOFT_LOCK_TTL = Duration.ofMinutes(5);
  private static final Duration HARD_LOCK_TTL = Duration.ofMinutes(30);

  private final CartRepository cartRepository;
  private final CartItemRepository cartItemRepository;
  private final SeatRepository seatRepository;
  private final GaInventoryClaimRepository gaInventoryClaimRepository;
  private final SeatLockAuditLogRepository seatLockAuditLogRepository;
  private final SlotValidationService slotValidationService;
  private final SeatLockRedisService seatLockRedisService;
  private final GaInventoryRedisService gaInventoryRedisService;
  private final CartPricingService cartPricingService;
  private final ConflictResolutionService conflictResolutionService;
  private final CartMapper cartMapper;
  private final EbTicketService ebTicketService;
  private final EbTokenStore ebTokenStore;
  private final ApplicationEventPublisher eventPublisher;
  private final StringRedisTemplate stringRedisTemplate;

  public CartService(
      CartRepository cartRepository,
      CartItemRepository cartItemRepository,
      SeatRepository seatRepository,
      GaInventoryClaimRepository gaInventoryClaimRepository,
      SeatLockAuditLogRepository seatLockAuditLogRepository,
      SlotValidationService slotValidationService,
      SeatLockRedisService seatLockRedisService,
      GaInventoryRedisService gaInventoryRedisService,
      CartPricingService cartPricingService,
      ConflictResolutionService conflictResolutionService,
      CartMapper cartMapper,
      EbTicketService ebTicketService,
      EbTokenStore ebTokenStore,
      ApplicationEventPublisher eventPublisher,
      StringRedisTemplate stringRedisTemplate) {
    this.cartRepository = cartRepository;
    this.cartItemRepository = cartItemRepository;
    this.seatRepository = seatRepository;
    this.gaInventoryClaimRepository = gaInventoryClaimRepository;
    this.seatLockAuditLogRepository = seatLockAuditLogRepository;
    this.slotValidationService = slotValidationService;
    this.seatLockRedisService = seatLockRedisService;
    this.gaInventoryRedisService = gaInventoryRedisService;
    this.cartPricingService = cartPricingService;
    this.conflictResolutionService = conflictResolutionService;
    this.cartMapper = cartMapper;
    this.ebTicketService = ebTicketService;
    this.ebTokenStore = ebTokenStore;
    this.eventPublisher = eventPublisher;
    this.stringRedisTemplate = stringRedisTemplate;
  }

  @Transactional
  public CartResponse addSeat(Long userId, Long orgId, String role, AddSeatRequest request) {
    slotValidationService.ensureUserActive(role);
    SlotSummaryDto slot = slotValidationService.requireActiveAndSynced(request.slotId());
    List<PricingTierDto> tiers = cartPricingService.getSlotPricingCached(request.slotId());
    Map<Long, PricingTierDto> tierById =
        tiers.stream().collect(Collectors.toMap(PricingTierDto::tierId, Function.identity()));

    PricingTierDto tier = tierById.get(request.tierId());
    if (tier == null) {
      throw new BusinessRuleException("Pricing tier not found for slot", "TIER_NOT_FOUND");
    }
    slotValidationService.ensureTierSynced(request.tierId(), tier.ebTicketClassId());
    assertOrgConnected(orgId);

    Cart cart = getOrCreatePendingCart(userId, slot, orgId, tier.price().currency());

    if (slot.seatingMode() == com.eventplatform.shared.common.enums.SeatingMode.RESERVED) {
      if (request.seatId() == null) {
        throw new BusinessRuleException("seatId is required for RESERVED mode", "SEAT_ID_REQUIRED");
      }
      addReservedSeat(userId, cart, tierById, request, tier);
    } else {
      addGaTier(userId, cart, request, tier);
    }

    return buildCartResponse(cart, tierById);
  }

  @Transactional
  public CartResponse removeItem(Long userId, Long itemId) {
    CartItem item =
        cartItemRepository
            .findByIdAndCartUserId(itemId, userId)
            .orElseThrow(() -> new CartItemNotFoundException(itemId));

    Cart cart = item.getCart();
    if (item.getSeatId() != null) {
      Seat seat =
          seatRepository
              .findByIdWithLock(item.getSeatId())
              .orElseThrow(() -> new BusinessRuleException("Seat not found", "SEAT_NOT_FOUND"));
      if (seat.getLockState() == SeatLockState.HARD_LOCKED
          || seat.getLockState() == SeatLockState.PAYMENT_PENDING
          || seat.getLockState() == SeatLockState.CONFIRMED) {
        throw new BaseException(
            "Seat removal not allowed",
            "SEAT_REMOVAL_NOT_ALLOWED",
            HttpStatus.CONFLICT,
            Map.of("lockState", seat.getLockState().name())) {};
      }
      SeatLockState previous = seat.getLockState();
      seat.release(LockReleaseReason.USER_REMOVED);
      seatRepository.save(seat);
      seatLockRedisService.release(seat.getId(), userId);
      writeAudit(
          seat,
          userId,
          previous,
          SeatLockState.AVAILABLE,
          SeatLockEvent.RELEASE,
          LockReleaseReason.USER_REMOVED.name(),
          null);
    } else if (item.getGaClaimId() != null) {
      gaInventoryClaimRepository
          .findById(item.getGaClaimId())
          .ifPresent(
              claim -> {
                gaInventoryRedisService.restore(
                    claim.getShowSlotId(), claim.getPricingTierId(), claim.getQuantity());
                gaInventoryClaimRepository.delete(claim);
              });
    }

    cart.removeItem(item);
    cartItemRepository.delete(item);

    Map<Long, PricingTierDto> tierById = fetchTierMap(cart.getShowSlotId());
    return buildCartResponse(cart, tierById);
  }

  @Transactional(readOnly = true)
  public CartResponse getCart(Long userId, Long cartId) {
    Cart cart =
        cartRepository.findById(cartId).orElseThrow(() -> new CartNotFoundException(cartId));
    if (!Objects.equals(cart.getUserId(), userId)) {
      throw new CartNotFoundException(cartId);
    }
    return buildCartResponse(cart, fetchTierMap(cart.getShowSlotId()));
  }

  @Transactional
  public CartResponse confirm(
      Long userId, Long orgId, String userEmail, ConfirmCartRequest request) {
    Cart cart =
        cartRepository
            .findById(request.cartId())
            .orElseThrow(() -> new CartNotFoundException(request.cartId()));
    if (!Objects.equals(cart.getUserId(), userId)) {
      throw new CartNotFoundException(request.cartId());
    }
    cart.ensurePending();
    if (cart.isExpired(Instant.now())) {
      throw new CartExpiredException(cart.getId(), cart.getExpiresAt());
    }

    List<PricingTierDto> tiers = cartPricingService.getSlotPricingCached(cart.getShowSlotId());
    Map<Long, PricingTierDto> tierById =
        tiers.stream().collect(Collectors.toMap(PricingTierDto::tierId, Function.identity()));
    SlotSummaryDto slot = slotValidationService.requireActiveAndSynced(cart.getShowSlotId());

    Set<Long> expiredSeatIds =
        cart.getItems().stream()
            .map(CartItem::getSeatId)
            .filter(Objects::nonNull)
            .filter(seatId -> !seatLockRedisService.isOwnedBy(seatId, userId))
            .collect(Collectors.toSet());
    if (!expiredSeatIds.isEmpty()) {
      throw new SoftLockExpiredException(expiredSeatIds);
    }

    Map<String, Long> ticketClassToTier =
        tierById.values().stream()
            .filter(t -> t.ebTicketClassId() != null)
            .collect(
                Collectors.toMap(
                    PricingTierDto::ebTicketClassId, PricingTierDto::tierId, (a, b) -> a));

    for (String ticketClassId :
        cart.getItems().stream().map(CartItem::getEbTicketClassId).collect(Collectors.toSet())) {
      EbTicketClassResponse ticketClass;
      try {
        ticketClass = ebTicketService.getTicketClass(slot.ebEventId(), ticketClassId);
      } catch (EbIntegrationException ex) {
        throw new BaseException(
            "Eventbrite unavailable",
            "EB_UNAVAILABLE",
            HttpStatus.SERVICE_UNAVAILABLE,
            Map.of("retryAfterSeconds", 10)) {};
      }
      if (ticketClass != null && "SOLD_OUT".equalsIgnoreCase(ticketClass.onSaleStatus())) {
        Long soldOutTierId = ticketClassToTier.get(ticketClassId);
        releaseTierLocks(cart, userId, soldOutTierId);
        List<Map<String, Object>> alternativeTiers =
            tierById.values().stream()
                .filter(t -> !Objects.equals(t.tierId(), soldOutTierId))
                .map(t -> Map.<String, Object>of("tierId", t.tierId(), "tierName", t.tierName()))
                .toList();
        throw new TierSoldOutException(soldOutTierId, Map.of("otherTiers", alternativeTiers));
      }
    }

    for (CartItem item : cart.getItems()) {
      if (item.getSeatId() != null) {
        Seat seat =
            seatRepository
                .findByIdWithLock(item.getSeatId())
                .orElseThrow(() -> new BusinessRuleException("Seat not found", "SEAT_NOT_FOUND"));
        try {
          if (!seatLockRedisService.extend(seat.getId(), userId, HARD_LOCK_TTL)) {
            throw new HardLockException("Failed to extend Redis lock for seat=" + seat.getId());
          }
          SeatLockState previous = seat.getLockState();
          seat.hardLock(HARD_LOCK_TTL);
          seatRepository.save(seat);
          writeAudit(
              seat, userId, previous, SeatLockState.HARD_LOCKED, SeatLockEvent.CONFIRM, null, null);
        } catch (RuntimeException ex) {
          seatLockRedisService.release(seat.getId(), userId);
          throw ex;
        }
      } else if (item.getGaClaimId() != null) {
        gaInventoryClaimRepository
            .findById(item.getGaClaimId())
            .ifPresent(
                claim -> {
                  claim.hardLock(Instant.now().plus(HARD_LOCK_TTL));
                  gaInventoryClaimRepository.save(claim);
                });
      }
    }

    cart.extendTtl(HARD_LOCK_TTL);
    cartRepository.save(cart);

    List<CartItem> confirmedItems = new ArrayList<>(cart.getItems());
    CartPricingResult pricing = cartPricingService.recompute(cart, confirmedItems);

    // Subtract coupon discount (set by promotions via CouponAppliedEvent) from the
    // post-group-discount total.  Floor at zero to guard against oversized coupons.
    java.math.BigDecimal netTotal =
        pricing
            .total()
            .amount()
            .subtract(cart.getCouponDiscountAmount().amount())
            .max(java.math.BigDecimal.ZERO);
    long totalAmountInSmallestUnit =
        netTotal
            .multiply(java.math.BigDecimal.valueOf(100))
            .setScale(0, java.math.RoundingMode.HALF_UP)
            .longValue();
    String pricingCurrency = pricing.total().currency().toLowerCase();

    eventPublisher.publishEvent(
        new CartAssembledEvent(
            cart.getId(),
            cart.getShowSlotId(),
            cart.getUserId(),
            cart.getOrgId(),
            slot.ebEventId(),
            cart.getCouponCode(),
            totalAmountInSmallestUnit,
            pricingCurrency,
            userEmail));

    return buildCartResponse(cart, tierById);
  }

  @Transactional
  public void abandon(Long userId, AbandonCartRequest request) {
    Cart cart =
        cartRepository
            .findById(request.cartId())
            .orElseThrow(() -> new CartNotFoundException(request.cartId()));
    if (!Objects.equals(cart.getUserId(), userId)) {
      throw new CartNotFoundException(request.cartId());
    }

    for (CartItem item : new ArrayList<>(cart.getItems())) {
      if (item.getSeatId() != null) {
        Seat seat = seatRepository.findByIdWithLock(item.getSeatId()).orElse(null);
        if (seat != null && seat.getLockState() != SeatLockState.CONFIRMED) {
          SeatLockState previous = seat.getLockState();
          seat.release(LockReleaseReason.CART_ABANDONED);
          seatRepository.save(seat);
          seatLockRedisService.release(seat.getId(), userId);
          writeAudit(
              seat,
              userId,
              previous,
              SeatLockState.AVAILABLE,
              SeatLockEvent.RELEASE,
              LockReleaseReason.CART_ABANDONED.name(),
              null);
        }
      } else if (item.getGaClaimId() != null) {
        gaInventoryClaimRepository
            .findById(item.getGaClaimId())
            .ifPresent(
                claim -> {
                  gaInventoryRedisService.restore(
                      claim.getShowSlotId(), claim.getPricingTierId(), claim.getQuantity());
                  gaInventoryClaimRepository.delete(claim);
                });
      }
      cart.removeItem(item);
      cartItemRepository.delete(item);
    }

    cart.abandon();
    cartRepository.save(cart);
  }

  @Transactional
  public void handlePaymentTimeout(Long cartId, List<Long> seatIds, Long userId) {
    eventPublisher.publishEvent(new PaymentTimeoutEvent(cartId, seatIds, userId));
  }

  @Transactional
  public void onBookingConfirmed(Long cartId, List<Long> seatIds, String bookingRef, Long userId) {
    Cart cart =
        cartRepository.findById(cartId).orElseThrow(() -> new CartNotFoundException(cartId));
    for (Long seatId : seatIds) {
      Seat seat = seatRepository.findByIdWithLock(seatId).orElse(null);
      if (seat == null) {
        continue;
      }
      if (seat.getLockState() == SeatLockState.CONFIRMED) {
        continue;
      }
      SeatLockState previous = seat.getLockState();
      if (previous == SeatLockState.HARD_LOCKED) {
        seat.markPaymentPending();
      }
      seat.confirm(bookingRef);
      seatRepository.save(seat);
      seatLockRedisService.release(seatId, userId);
      writeAudit(
          seat,
          userId,
          previous,
          SeatLockState.CONFIRMED,
          SeatLockEvent.CONFIRM_PAYMENT,
          "STRIPE_PAYMENT_CONFIRMED",
          bookingRef);
    }
    cart.confirm();
    cartRepository.save(cart);
  }

  @Transactional
  public void onPaymentFailed(Long cartId, List<Long> seatIds, Long userId) {
    Cart cart =
        cartRepository.findById(cartId).orElseThrow(() -> new CartNotFoundException(cartId));
    for (Long seatId : seatIds) {
      Seat seat = seatRepository.findByIdWithLock(seatId).orElse(null);
      if (seat == null
          || seat.getLockState() == SeatLockState.AVAILABLE
          || seat.getLockState() == SeatLockState.CONFIRMED) {
        continue;
      }
      SeatLockState previous = seat.getLockState();
      seat.release(LockReleaseReason.PAYMENT_FAILED);
      seatRepository.save(seat);
      seatLockRedisService.release(seatId, userId);
      writeAudit(
          seat,
          userId,
          previous,
          SeatLockState.AVAILABLE,
          SeatLockEvent.RELEASE,
          LockReleaseReason.PAYMENT_FAILED.name(),
          null);
    }
    cart.abandon();
    cartRepository.save(cart);
  }

  private Cart getOrCreatePendingCart(
      Long userId, SlotSummaryDto slot, Long orgId, String currency) {
    return cartRepository
        .findByUserIdAndShowSlotIdAndStatus(userId, slot.slotId(), CartStatus.PENDING)
        .orElseGet(
            () -> {
              try {
                Cart created =
                    new Cart(
                        userId, slot.slotId(), orgId, slot.seatingMode(), SOFT_LOCK_TTL, currency);
                return cartRepository.save(created);
              } catch (DataIntegrityViolationException ex) {
                return cartRepository
                    .findByUserIdAndShowSlotIdAndStatus(userId, slot.slotId(), CartStatus.PENDING)
                    .orElseThrow(
                        () ->
                            new BusinessRuleException(
                                "Pending cart already exists", "CART_ALREADY_EXISTS"));
              }
            });
  }

  private void addReservedSeat(
      Long userId,
      Cart cart,
      Map<Long, PricingTierDto> tierById,
      AddSeatRequest request,
      PricingTierDto tier) {
    Seat seat =
        seatRepository
            .findByIdWithLock(request.seatId())
            .orElseThrow(() -> new BusinessRuleException("Seat not found", "SEAT_NOT_FOUND"));

    if (!Objects.equals(seat.getShowSlotId(), request.slotId())
        || !Objects.equals(seat.getPricingTierId(), request.tierId())) {
      throw new BusinessRuleException("Seat does not belong to slot/tier", "SEAT_MISMATCH");
    }

    AcquireResult result = seatLockRedisService.acquire(seat.getId(), userId, SOFT_LOCK_TTL);
    if (result == AcquireResult.CONFLICT) {
      throw new SeatUnavailableException(
          seat.getId(), conflictResolutionService.alternativesFor(seat));
    }

    if (result == AcquireResult.ACQUIRED && !seat.isSelectable(Instant.now())) {
      seatLockRedisService.release(seat.getId(), userId);
      throw new SeatUnavailableException(
          seat.getId(), conflictResolutionService.alternativesFor(seat));
    }

    try {
      if (result == AcquireResult.ACQUIRED) {
        SeatLockState previous = seat.getLockState();
        seat.softLock(userId, SOFT_LOCK_TTL);
        seatRepository.save(seat);
        writeAudit(
            seat, userId, previous, SeatLockState.SOFT_LOCKED, SeatLockEvent.SELECT, null, null);
      }

      if (!cartItemRepository.existsByCartIdAndSeatId(cart.getId(), seat.getId())) {
        CartItem item =
            new CartItem(
                seat.getId(),
                null,
                seat.getPricingTierId(),
                seat.getEbTicketClassId(),
                tier.price(),
                1);
        cart.addItem(item);
        cartItemRepository.save(item);
      }
    } catch (RuntimeException ex) {
      seatLockRedisService.release(seat.getId(), userId);
      throw new SeatLockException("DB failure while soft-locking seat=" + seat.getId());
    }
  }

  private void addGaTier(Long userId, Cart cart, AddSeatRequest request, PricingTierDto tier) {
    String blocked =
        stringRedisTemplate
            .opsForValue()
            .get("tier:blocked:" + request.slotId() + ":" + request.tierId());
    if (blocked != null) {
      throw new TierBlockedException(request.tierId());
    }

    long claimResult =
        gaInventoryRedisService.claim(request.slotId(), request.tierId(), request.quantity());
    if (claimResult == -2) {
      throw new BaseException(
          "GA counter not initialized",
          "GA_COUNTER_NOT_INITIALIZED",
          HttpStatus.SERVICE_UNAVAILABLE) {};
    }
    if (claimResult < 0) {
      throw new BaseException(
          "Insufficient GA inventory",
          "INSUFFICIENT_GA_INVENTORY",
          HttpStatus.CONFLICT,
          Map.of("tierId", request.tierId(), "requested", request.quantity(), "available", 0)) {};
    }

    GaInventoryClaim claim =
        new GaInventoryClaim(
            request.slotId(),
            request.tierId(),
            userId,
            cart.getId(),
            request.quantity(),
            Instant.now().plus(SOFT_LOCK_TTL));
    claim = gaInventoryClaimRepository.save(claim);

    CartItem item =
        new CartItem(
            null,
            claim.getId(),
            request.tierId(),
            tier.ebTicketClassId(),
            tier.price(),
            request.quantity());
    cart.addItem(item);
    cartItemRepository.save(item);
  }

  private Map<Long, PricingTierDto> fetchTierMap(Long slotId) {
    return cartPricingService.getSlotPricingCached(slotId).stream()
        .collect(Collectors.toMap(PricingTierDto::tierId, Function.identity()));
  }

  private CartResponse buildCartResponse(Cart cart, Map<Long, PricingTierDto> tierById) {
    List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
    CartPricingResult pricing = cartPricingService.recompute(cart, items);
    cartRepository.save(cart);

    Map<Long, String> tierNames =
        tierById.values().stream()
            .collect(
                Collectors.toMap(PricingTierDto::tierId, PricingTierDto::tierName, (a, b) -> a));

    Map<Long, String> seatNumbers = new HashMap<>();
    List<Long> seatIds = items.stream().map(CartItem::getSeatId).filter(Objects::nonNull).toList();
    if (!seatIds.isEmpty()) {
      seatRepository
          .findAllById(seatIds)
          .forEach(seat -> seatNumbers.put(seat.getId(), seat.getSeatNumber()));
    }

    return cartMapper.toResponse(cart, items, pricing, tierNames, seatNumbers);
  }

  private void writeAudit(
      Seat seat,
      Long userId,
      SeatLockState from,
      SeatLockState to,
      SeatLockEvent event,
      String reason,
      String bookingRef) {
    seatLockAuditLogRepository.save(
        new SeatLockAuditLog(
            seat.getId(), null, seat.getShowSlotId(), userId, from, to, event, reason, bookingRef));
  }

  private void assertOrgConnected(Long orgId) {
    try {
      ebTokenStore.getAccessToken(orgId);
    } catch (EbAuthException ex) {
      throw new BaseException(
          "Organization is not connected to Eventbrite",
          "ORG_NOT_CONNECTED",
          HttpStatus.SERVICE_UNAVAILABLE) {};
    }
  }

  private void releaseTierLocks(Cart cart, Long userId, Long soldOutTierId) {
    for (CartItem item : cart.getItems()) {
      if (!Objects.equals(item.getPricingTierId(), soldOutTierId)) {
        continue;
      }
      if (item.getSeatId() != null) {
        seatRepository
            .findByIdWithLock(item.getSeatId())
            .ifPresent(
                seat -> {
                  if (seat.getLockState() == SeatLockState.SOFT_LOCKED) {
                    SeatLockState previous = seat.getLockState();
                    seat.release(LockReleaseReason.TIER_SOLD_OUT);
                    seatRepository.save(seat);
                    seatLockRedisService.release(seat.getId(), userId);
                    writeAudit(
                        seat,
                        userId,
                        previous,
                        SeatLockState.AVAILABLE,
                        SeatLockEvent.RELEASE,
                        LockReleaseReason.TIER_SOLD_OUT.name(),
                        null);
                  }
                });
      }
      if (item.getGaClaimId() != null) {
        gaInventoryClaimRepository
            .findById(item.getGaClaimId())
            .ifPresent(
                claim -> {
                  gaInventoryRedisService.restore(
                      claim.getShowSlotId(), claim.getPricingTierId(), claim.getQuantity());
                  gaInventoryClaimRepository.delete(claim);
                });
      }
    }
  }
}
