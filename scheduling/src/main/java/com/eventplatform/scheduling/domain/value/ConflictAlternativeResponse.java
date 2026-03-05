package com.eventplatform.scheduling.domain.value;

import java.util.List;

public record ConflictAlternativeResponse(
    List<TimeWindowOption> sameVenueAlternatives,
    List<VenueOption> nearbyVenueAlternatives,
    List<TimeWindowOption> adjustedTimeOptions) {}
