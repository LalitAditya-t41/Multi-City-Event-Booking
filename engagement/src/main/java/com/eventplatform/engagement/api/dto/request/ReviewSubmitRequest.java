package com.eventplatform.engagement.api.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewSubmitRequest(
    @NotNull Long eventId,
    @NotNull @Min(1) @Max(5) Integer rating,
    @NotBlank @Size(max = 100) String title,
    @NotBlank @Size(max = 2000) String body) {}
