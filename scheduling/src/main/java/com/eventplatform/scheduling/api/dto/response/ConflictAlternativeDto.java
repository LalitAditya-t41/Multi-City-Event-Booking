package com.eventplatform.scheduling.api.dto.response;

import java.util.List;

public record ConflictAlternativeDto(
    List<TimeWindowOptionDto> sameVenueAlternatives,
    List<VenueOptionDto> nearbyVenueAlternatives,
    List<TimeWindowOptionDto> adjustedTimeOptions) {}
