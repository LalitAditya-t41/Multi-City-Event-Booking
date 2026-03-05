package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.request.EbVenueCreateRequest;
import com.eventplatform.shared.eventbrite.dto.response.EbVenueResponse;

public interface EbVenueService {
  EbVenueResponse createVenue(Long organizationId, EbVenueCreateRequest request);

  EbVenueResponse updateVenue(Long organizationId, String ebVenueId, EbVenueCreateRequest request);

  EbVenueResponse getVenue(Long organizationId, String ebVenueId);
}
