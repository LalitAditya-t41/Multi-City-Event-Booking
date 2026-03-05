package com.eventplatform.scheduling.api.dto.response;

import java.time.ZonedDateTime;

public record TimeWindowOptionDto(ZonedDateTime proposedStart, ZonedDateTime proposedEnd) {}
