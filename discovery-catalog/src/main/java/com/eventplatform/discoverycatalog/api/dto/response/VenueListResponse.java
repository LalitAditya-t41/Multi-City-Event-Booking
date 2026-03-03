package com.eventplatform.discoverycatalog.api.dto.response;

import java.util.List;

public record VenueListResponse(List<VenueResponse> venues, PaginationInfo pagination) {
}
