package com.eventplatform.bookinginventory.statemachine;

import com.eventplatform.bookinginventory.domain.enums.SeatLockEvent;
import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SeatLockStateMachineConfig {

  private final Map<SeatLockState, Map<SeatLockEvent, SeatLockState>> transitions =
      new EnumMap<>(SeatLockState.class);

  public SeatLockStateMachineConfig() {
    transitions.put(
        SeatLockState.AVAILABLE, Map.of(SeatLockEvent.SELECT, SeatLockState.SOFT_LOCKED));
    transitions.put(
        SeatLockState.SOFT_LOCKED,
        Map.of(
            SeatLockEvent.CONFIRM, SeatLockState.HARD_LOCKED,
            SeatLockEvent.RELEASE, SeatLockState.AVAILABLE));
    transitions.put(
        SeatLockState.HARD_LOCKED,
        Map.of(
            SeatLockEvent.PAYMENT_INITIATE, SeatLockState.PAYMENT_PENDING,
            SeatLockEvent.RELEASE, SeatLockState.AVAILABLE));
    transitions.put(
        SeatLockState.PAYMENT_PENDING,
        Map.of(
            SeatLockEvent.CONFIRM_PAYMENT, SeatLockState.CONFIRMED,
            SeatLockEvent.RELEASE, SeatLockState.AVAILABLE));
    transitions.put(SeatLockState.CONFIRMED, Map.of());
    transitions.put(SeatLockState.RELEASED, Map.of(SeatLockEvent.RELEASE, SeatLockState.AVAILABLE));
  }

  public SeatLockState nextState(SeatLockState current, SeatLockEvent event) {
    return transitions.getOrDefault(current, Map.of()).get(event);
  }
}
