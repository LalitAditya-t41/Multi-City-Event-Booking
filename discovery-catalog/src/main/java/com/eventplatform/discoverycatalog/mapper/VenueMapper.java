package com.eventplatform.discoverycatalog.mapper;

import com.eventplatform.discoverycatalog.api.dto.response.VenueResponse;
import com.eventplatform.discoverycatalog.domain.Venue;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface VenueMapper {
    @Mapping(target = "cityId", source = "cityId")
    VenueResponse toResponse(Venue venue);
}
