package com.eventplatform.scheduling.statemachine.action;

import com.eventplatform.scheduling.domain.ShowSlot;
import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;
import org.springframework.stereotype.Component;

@Component
public class SlotStatusUpdateAction {

    public void apply(ShowSlot slot, ShowSlotStatus targetStatus) {
        switch (targetStatus) {
            case PENDING_SYNC -> slot.markPendingSync();
            case ACTIVE -> slot.markActive();
            case CANCELLED -> slot.markCancelled();
            case DRAFT -> { }
        }
    }
}
