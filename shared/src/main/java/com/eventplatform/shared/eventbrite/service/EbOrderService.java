package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.response.EbOrderResponse;

public interface EbOrderService {
    EbOrderResponse getOrder(String orderId);
}
