package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.EbCapacityResponse;

public interface EbCapacityService {
    EbCapacityResponse updateCapacityTier(String eventId, int capacity);
}
