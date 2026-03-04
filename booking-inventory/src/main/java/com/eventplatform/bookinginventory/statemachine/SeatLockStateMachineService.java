package com.eventplatform.bookinginventory.statemachine;

import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.domain.enums.SeatLockEvent;
import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import com.eventplatform.bookinginventory.statemachine.action.ConfirmAction;
import com.eventplatform.bookinginventory.statemachine.action.HardLockAction;
import com.eventplatform.bookinginventory.statemachine.action.PaymentPendingAction;
import com.eventplatform.bookinginventory.statemachine.action.ReleaseAction;
import com.eventplatform.bookinginventory.statemachine.action.SoftLockAction;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import org.springframework.stereotype.Service;

@Service
public class SeatLockStateMachineService {

    private final SeatLockStateMachineConfig config;
    private final SoftLockAction softLockAction;
    private final HardLockAction hardLockAction;
    private final PaymentPendingAction paymentPendingAction;
    private final ConfirmAction confirmAction;
    private final ReleaseAction releaseAction;

    public SeatLockStateMachineService(
        SeatLockStateMachineConfig config,
        SoftLockAction softLockAction,
        HardLockAction hardLockAction,
        PaymentPendingAction paymentPendingAction,
        ConfirmAction confirmAction,
        ReleaseAction releaseAction
    ) {
        this.config = config;
        this.softLockAction = softLockAction;
        this.hardLockAction = hardLockAction;
        this.paymentPendingAction = paymentPendingAction;
        this.confirmAction = confirmAction;
        this.releaseAction = releaseAction;
    }

    public void sendEvent(Seat seat, SeatLockEvent event, SeatActionContext context) {
        SeatLockState next = config.nextState(seat.getLockState(), event);
        if (next == null) {
            throw new BusinessRuleException(
                "Invalid seat transition: " + seat.getLockState() + " -> " + event,
                "INVALID_SEAT_TRANSITION"
            );
        }
        switch (event) {
            case SELECT -> softLockAction.apply(seat, context);
            case CONFIRM -> hardLockAction.apply(seat, context);
            case CHECKOUT_INITIATE -> paymentPendingAction.apply(seat, context);
            case CONFIRM_PAYMENT -> confirmAction.apply(seat, context);
            case RELEASE -> releaseAction.apply(seat, context);
        }
    }

    public record SeatActionContext(Long userId, String orderId, String reason) {
    }
}
