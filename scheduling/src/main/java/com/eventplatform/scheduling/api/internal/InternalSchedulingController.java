package com.eventplatform.scheduling.api.internal;

import com.eventplatform.scheduling.domain.ShowSlot;
import com.eventplatform.scheduling.service.ShowSlotService;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/scheduling/slots")
public class InternalSchedulingController {

    private final ShowSlotService showSlotService;

    public InternalSchedulingController(ShowSlotService showSlotService) {
        this.showSlotService = showSlotService;
    }

    @GetMapping("/{slotId}/timing")
    public SlotTimingResponse getSlotTiming(@PathVariable Long slotId) {
        ShowSlot slot = showSlotService.getSlot(slotId);
        return new SlotTimingResponse(slotId, slot.getStartTime().toInstant());
    }

    public record SlotTimingResponse(Long slotId, Instant startTime) {
    }
}
