package com.eventplatform.discoverycatalog.api.dto.response;

public record CityResponse(
    Long id,
    String name,
    String description,
    String state,
    String countryCode,
    String latitude,
    String longitude) {}
