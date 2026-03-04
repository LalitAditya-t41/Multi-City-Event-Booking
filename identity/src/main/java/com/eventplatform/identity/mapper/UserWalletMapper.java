package com.eventplatform.identity.mapper;

import com.eventplatform.identity.api.dto.response.UserWalletResponse;
import com.eventplatform.identity.domain.UserWallet;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserWalletMapper {

    @Mapping(target = "balance", source = "balance.amount")
    @Mapping(target = "currency", source = "balance.currency")
    UserWalletResponse toResponse(UserWallet wallet);
}
