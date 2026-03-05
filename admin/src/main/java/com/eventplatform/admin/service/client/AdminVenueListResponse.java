package com.eventplatform.admin.service.client;

import java.util.List;

public record AdminVenueListResponse(List<AdminVenueResponse> venues, Object pagination) {}
