package com.eventplatform.bookinginventory.scheduler;

import com.eventplatform.bookinginventory.domain.Cart;
import com.eventplatform.bookinginventory.domain.CartItem;
import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.domain.SeatLockAuditLog;
import com.eventplatform.bookinginventory.domain.enums.CartStatus;
import com.eventplatform.bookinginventory.domain.enums.LockReleaseReason;
import com.eventplatform.bookinginventory.domain.enums.SeatLockEvent;
import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import com.eventplatform.bookinginventory.repository.CartItemRepository;
import com.eventplatform.bookinginventory.repository.CartRepository;
import com.eventplatform.bookinginventory.repository.SeatLockAuditLogRepository;
import com.eventplatform.bookinginventory.repository.SeatRepository;
import com.eventplatform.bookinginventory.service.redis.SeatLockRedisService;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
public class CartExpiryCleanupJob {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final SeatRepository seatRepository;
    private final SeatLockAuditLogRepository auditRepository;
    private final SeatLockRedisService seatLockRedisService;

    public CartExpiryCleanupJob(
        CartRepository cartRepository,
        CartItemRepository cartItemRepository,
        SeatRepository seatRepository,
        SeatLockAuditLogRepository auditRepository,
        SeatLockRedisService seatLockRedisService
    ) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.seatRepository = seatRepository;
        this.auditRepository = auditRepository;
        this.seatLockRedisService = seatLockRedisService;
    }

    @Scheduled(fixedDelay = 300000)
    @Transactional
    public void expirePendingCarts() {
        for (Cart cart : cartRepository.findByStatusAndExpiresAtBefore(CartStatus.PENDING, Instant.now())) {
            cart.expire();
            cartRepository.save(cart);
            for (CartItem item : cartItemRepository.findByCartId(cart.getId())) {
                if (item.getSeatId() == null) {
                    continue;
                }
                Seat seat = seatRepository.findByIdWithLock(item.getSeatId()).orElse(null);
                if (seat == null) {
                    continue;
                }
                if (seat.getLockState() != SeatLockState.SOFT_LOCKED) {
                    continue;
                }
                SeatLockState from = seat.getLockState();
                seat.release(LockReleaseReason.CART_ABANDONED);
                seatRepository.save(seat);
                seatLockRedisService.release(seat.getId(), cart.getUserId());
                auditRepository.save(new SeatLockAuditLog(
                    seat.getId(),
                    null,
                    seat.getShowSlotId(),
                    cart.getUserId(),
                    from,
                    SeatLockState.AVAILABLE,
                    SeatLockEvent.RELEASE,
                    LockReleaseReason.CART_ABANDONED.name(),
                    null
                ));
            }
        }
    }
}
