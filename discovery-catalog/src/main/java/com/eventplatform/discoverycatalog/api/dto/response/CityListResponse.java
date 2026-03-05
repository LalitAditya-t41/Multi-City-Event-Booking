package com.eventplatform.discoverycatalog.api.dto.response;

import java.util.List;

public record CityListResponse(List<CityResponse> cities, long totalCount) {}
