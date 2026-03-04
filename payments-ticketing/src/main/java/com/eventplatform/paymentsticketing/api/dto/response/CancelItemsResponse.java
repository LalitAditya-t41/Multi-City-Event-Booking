package com.eventplatform.paymentsticketing.api.dto.response;

import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import java.util.List;

public record CancelItemsResponse(
    String bookingRef,
    BookingStatus bookingStatus,
    List<Long> cancelledItemIds,
    ItemCancellationRefundResponse refund
) {

    public record ItemCancellationRefundResponse(
        String type,
        Integer percent,
        Long amount,
        String currency,
        String status
    ) {
    }
}
