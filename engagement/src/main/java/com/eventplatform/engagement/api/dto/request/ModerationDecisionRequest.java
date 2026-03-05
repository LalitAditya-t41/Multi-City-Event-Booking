package com.eventplatform.engagement.api.dto.request;

import com.eventplatform.engagement.service.model.ManualModerationDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ModerationDecisionRequest(
    @NotNull ManualModerationDecision decision, @Size(max = 500) String reason) {}
