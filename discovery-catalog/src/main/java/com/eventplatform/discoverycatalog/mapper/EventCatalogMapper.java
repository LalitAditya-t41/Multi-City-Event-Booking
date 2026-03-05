package com.eventplatform.discoverycatalog.mapper;

import com.eventplatform.discoverycatalog.api.dto.response.EventCatalogItemResponse;
import com.eventplatform.discoverycatalog.domain.EventCatalogItem;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface EventCatalogMapper {
  EventCatalogItemResponse toResponse(EventCatalogItem item);
}
