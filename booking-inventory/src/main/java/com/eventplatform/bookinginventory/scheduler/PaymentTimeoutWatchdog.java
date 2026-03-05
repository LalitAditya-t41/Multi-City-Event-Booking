package com.eventplatform.bookinginventory.scheduler;

import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.domain.SeatLockAuditLog;
import com.eventplatform.bookinginventory.domain.enums.LockReleaseReason;
import com.eventplatform.bookinginventory.domain.enums.SeatLockEvent;
import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import com.eventplatform.bookinginventory.repository.CartItemRepository;
import com.eventplatform.bookinginventory.repository.SeatLockAuditLogRepository;
import com.eventplatform.bookinginventory.repository.SeatRepository;
import com.eventplatform.bookinginventory.service.CartService;
import com.eventplatform.bookinginventory.service.redis.SeatLockRedisService;
import com.eventplatform.shared.common.service.PaymentConfirmationReader;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled watchdog that releases HARD_LOCKED / PAYMENT_PENDING seat locks whose TTL has expired
 * without a confirmed Stripe payment.
 *
 * <p>Payment confirmation check delegates to {@link PaymentConfirmationReader} from
 * shared/common/service.
 */
@Slf4j
@Component
public class PaymentTimeoutWatchdog {

  private final SeatRepository seatRepository;
  private final CartItemRepository cartItemRepository;
  private final SeatLockAuditLogRepository auditRepository;
  private final SeatLockRedisService seatLockRedisService;
  private final PaymentConfirmationReader paymentConfirmationReader;
  private final CartService cartService;

  public PaymentTimeoutWatchdog(
      SeatRepository seatRepository,
      CartItemRepository cartItemRepository,
      SeatLockAuditLogRepository auditRepository,
      SeatLockRedisService seatLockRedisService,
      PaymentConfirmationReader paymentConfirmationReader,
      CartService cartService) {
    this.seatRepository = seatRepository;
    this.cartItemRepository = cartItemRepository;
    this.auditRepository = auditRepository;
    this.seatLockRedisService = seatLockRedisService;
    this.paymentConfirmationReader = paymentConfirmationReader;
    this.cartService = cartService;
  }

  @Scheduled(fixedDelay = 60000)
  @Transactional
  public void releaseTimedOutPaymentLocks() {
    List<Seat> candidates =
        seatRepository.findAll().stream()
            .filter(
                seat ->
                    (seat.getLockState() == SeatLockState.HARD_LOCKED
                            || seat.getLockState() == SeatLockState.PAYMENT_PENDING)
                        && seat.getLockedUntil() != null
                        && seat.getLockedUntil().isBefore(Instant.now()))
            .toList();

    for (Seat seat : candidates) {
      try {
        if (isPaymentConfirmed(seat)) {
          continue;
        }
        SeatLockState from = seat.getLockState();
        Long userId = seat.getLockedByUserId();
        seat.release(LockReleaseReason.PAYMENT_TIMEOUT);
        seatRepository.save(seat);
        if (userId != null) {
          seatLockRedisService.release(seat.getId(), userId);
        }
        auditRepository.save(
            new SeatLockAuditLog(
                seat.getId(),
                null,
                seat.getShowSlotId(),
                userId,
                from,
                SeatLockState.AVAILABLE,
                SeatLockEvent.RELEASE,
                LockReleaseReason.PAYMENT_TIMEOUT.name(),
                null));
        if (userId != null) {
          cartService.handlePaymentTimeout(null, List.of(seat.getId()), userId);
        }
      } catch (Exception ex) {
        log.warn(
            "Payment timeout watchdog skip seatId={} reason={}", seat.getId(), ex.getMessage());
      }
    }
  }

  /**
   * Checks whether a confirmed payment exists for the cart that contains this seat. Uses shared
   * PaymentConfirmationReader.
   */
  private boolean isPaymentConfirmed(Seat seat) {
    Long cartId =
        cartItemRepository
            .findFirstBySeatId(seat.getId())
            .map(item -> item.getCart().getId())
            .orElse(null);
    return paymentConfirmationReader.isPaymentConfirmed(cartId);
  }
}
