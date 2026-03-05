package com.eventplatform.discoverycatalog.mapper;

import com.eventplatform.discoverycatalog.api.dto.response.CityResponse;
import com.eventplatform.discoverycatalog.domain.City;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CityMapper {
  CityResponse toResponse(City city);
}
