package com.eventplatform.identity.mapper;

import com.eventplatform.identity.api.dto.response.RegisterResponse;
import com.eventplatform.identity.api.dto.response.UserProfileResponse;
import com.eventplatform.identity.domain.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {

  @Mapping(target = "userId", source = "id")
  RegisterResponse toRegisterResponse(User user);

  @Mapping(target = "userId", source = "id")
  @Mapping(target = "status", expression = "java(user.getStatus().name())")
  UserProfileResponse toProfileResponse(User user);
}
