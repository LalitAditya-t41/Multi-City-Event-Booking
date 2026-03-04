package com.eventplatform.bookinginventory.scheduler;

import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.domain.SeatLockAuditLog;
import com.eventplatform.bookinginventory.domain.enums.LockReleaseReason;
import com.eventplatform.bookinginventory.domain.enums.SeatLockEvent;
import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import com.eventplatform.bookinginventory.repository.SeatLockAuditLogRepository;
import com.eventplatform.bookinginventory.repository.SeatRepository;
import com.eventplatform.bookinginventory.service.CartService;
import com.eventplatform.bookinginventory.service.redis.SeatLockRedisService;
import com.eventplatform.shared.eventbrite.dto.response.EbOrderResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.eventplatform.shared.eventbrite.service.EbOrderService;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
public class PaymentTimeoutWatchdog {

    private final SeatRepository seatRepository;
    private final SeatLockAuditLogRepository auditRepository;
    private final SeatLockRedisService seatLockRedisService;
    private final EbOrderService ebOrderService;
    private final CartService cartService;

    public PaymentTimeoutWatchdog(
        SeatRepository seatRepository,
        SeatLockAuditLogRepository auditRepository,
        SeatLockRedisService seatLockRedisService,
        EbOrderService ebOrderService,
        CartService cartService
    ) {
        this.seatRepository = seatRepository;
        this.auditRepository = auditRepository;
        this.seatLockRedisService = seatLockRedisService;
        this.ebOrderService = ebOrderService;
        this.cartService = cartService;
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void releaseTimedOutPaymentLocks() {
        List<Seat> candidates = seatRepository.findAll().stream()
            .filter(seat -> (seat.getLockState() == SeatLockState.HARD_LOCKED || seat.getLockState() == SeatLockState.PAYMENT_PENDING)
                && seat.getLockedUntil() != null
                && seat.getLockedUntil().isBefore(Instant.now()))
            .toList();

        for (Seat seat : candidates) {
            try {
                if (hasPlacedOrder(seat.getEbOrderId())) {
                    continue;
                }
                SeatLockState from = seat.getLockState();
                Long userId = seat.getLockedByUserId();
                seat.release(LockReleaseReason.PAYMENT_TIMEOUT);
                seatRepository.save(seat);
                if (userId != null) {
                    seatLockRedisService.release(seat.getId(), userId);
                }
                auditRepository.save(new SeatLockAuditLog(
                    seat.getId(),
                    null,
                    seat.getShowSlotId(),
                    userId,
                    from,
                    SeatLockState.AVAILABLE,
                    SeatLockEvent.RELEASE,
                    LockReleaseReason.PAYMENT_TIMEOUT.name(),
                    null
                ));
                if (userId != null) {
                    cartService.handlePaymentTimeout(null, List.of(seat.getId()), userId);
                }
            } catch (Exception ex) {
                log.warn("Payment timeout watchdog skip seatId={} reason={}", seat.getId(), ex.getMessage());
            }
        }
    }

    private boolean hasPlacedOrder(String ebOrderId) {
        if (ebOrderId == null || ebOrderId.isBlank()) {
            return false;
        }
        try {
            EbOrderResponse order = ebOrderService.getOrder(ebOrderId);
            return order != null && "placed".equalsIgnoreCase(order.status());
        } catch (EbIntegrationException ex) {
            throw ex;
        }
    }
}
