package com.eventplatform.scheduling.statemachine;

import com.eventplatform.scheduling.domain.ShowSlot;
import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;
import com.eventplatform.scheduling.statemachine.action.SlotStatusUpdateAction;
import com.eventplatform.scheduling.statemachine.guard.SlotTransitionGuard;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import org.springframework.stereotype.Service;

@Service
public class ShowSlotStateMachineService {

  private final SlotTransitionGuard guard;
  private final SlotStatusUpdateAction action;

  public ShowSlotStateMachineService(SlotTransitionGuard guard, SlotStatusUpdateAction action) {
    this.guard = guard;
    this.action = action;
  }

  public void sendEvent(ShowSlot slot, ShowSlotEvent event) {
    ShowSlotStatus nextStatus = guard.resolveNextStatus(slot.getStatus(), event);
    if (nextStatus == null) {
      throw new BusinessRuleException(
          "Invalid slot transition: " + slot.getStatus() + " -> " + event,
          "INVALID_SLOT_TRANSITION");
    }
    action.apply(slot, nextStatus);
  }
}
