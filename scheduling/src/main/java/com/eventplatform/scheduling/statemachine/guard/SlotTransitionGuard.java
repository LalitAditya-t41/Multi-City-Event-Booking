package com.eventplatform.scheduling.statemachine.guard;

import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;
import com.eventplatform.scheduling.statemachine.ShowSlotEvent;
import com.eventplatform.scheduling.statemachine.ShowSlotState;
import com.eventplatform.scheduling.statemachine.ShowSlotStateMachineConfig;
import org.springframework.stereotype.Component;

@Component
public class SlotTransitionGuard {

    private final ShowSlotStateMachineConfig config;

    public SlotTransitionGuard(ShowSlotStateMachineConfig config) {
        this.config = config;
    }

    public ShowSlotStatus resolveNextStatus(ShowSlotStatus current, ShowSlotEvent event) {
        ShowSlotState currentState = ShowSlotState.valueOf(current.name());
        ShowSlotState next = config.nextState(currentState, event);
        if (next == null) {
            return null;
        }
        return ShowSlotStatus.valueOf(next.name());
    }
}
