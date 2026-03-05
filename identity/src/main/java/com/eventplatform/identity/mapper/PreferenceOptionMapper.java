package com.eventplatform.identity.mapper;

import com.eventplatform.identity.api.dto.response.PreferenceOptionItemResponse;
import com.eventplatform.identity.domain.PreferenceOption;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PreferenceOptionMapper {

  @Mapping(target = "type", expression = "java(option.getType().name())")
  PreferenceOptionItemResponse toItemResponse(PreferenceOption option);
}
