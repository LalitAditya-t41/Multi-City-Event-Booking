package com.eventplatform.scheduling.statemachine;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ShowSlotStateMachineConfig {

    private final Map<ShowSlotState, Map<ShowSlotEvent, ShowSlotState>> transitions = new EnumMap<>(ShowSlotState.class);

    public ShowSlotStateMachineConfig() {
        transitions.put(ShowSlotState.DRAFT, Map.of(ShowSlotEvent.SUBMIT, ShowSlotState.PENDING_SYNC));
        transitions.put(ShowSlotState.PENDING_SYNC, Map.of(
            ShowSlotEvent.EB_PUBLISHED, ShowSlotState.ACTIVE,
            ShowSlotEvent.EB_FAILED, ShowSlotState.PENDING_SYNC,
            ShowSlotEvent.CANCEL, ShowSlotState.CANCELLED
        ));
        transitions.put(ShowSlotState.ACTIVE, Map.of(ShowSlotEvent.CANCEL, ShowSlotState.CANCELLED));
        transitions.put(ShowSlotState.CANCELLED, Map.of());
    }

    public ShowSlotState nextState(ShowSlotState current, ShowSlotEvent event) {
        return transitions.getOrDefault(current, Map.of()).get(event);
    }
}
