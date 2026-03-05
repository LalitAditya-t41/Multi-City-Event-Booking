package com.eventplatform.bookinginventory.service.client;

public record CatalogSeatResponse(
    Long id,
    String section,
    String rowLabel,
    String seatNumber,
    String tierName,
    boolean accessible) {}
