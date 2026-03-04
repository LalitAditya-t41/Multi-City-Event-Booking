package com.eventplatform.bookinginventory.statemachine.action;

import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.statemachine.SeatLockStateMachineService.SeatActionContext;
import org.springframework.stereotype.Component;

@Component
public class PaymentPendingAction {

    public void apply(Seat seat, SeatActionContext context) {
        seat.markPaymentPending();
    }
}
