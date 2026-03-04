package com.eventplatform.scheduling.domain.value;

import java.time.ZonedDateTime;

public record TimeWindowOption(
    ZonedDateTime proposedStart,
    ZonedDateTime proposedEnd
) {
}
