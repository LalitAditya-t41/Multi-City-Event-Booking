package com.eventplatform.shared.common.dto;

public record CartItemSnapshotDto(
    Long itemId,
    Long seatId,
    Long gaClaimId,
    String ticketClassId,
    Long unitPrice,
    String currency,
    Integer quantity
) {
}
