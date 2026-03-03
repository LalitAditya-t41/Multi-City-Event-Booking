package com.eventplatform.shared.eventbrite.dto;

public record EbInventoryTierRequest(
    String name,
    Integer quantity
) {
}
