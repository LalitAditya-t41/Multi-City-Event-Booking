package com.eventplatform.scheduling.service.client;

import java.util.List;

public record CatalogVenueListResponse(
    List<CatalogVenueResponse> venues,
    Object pagination
) {
}
