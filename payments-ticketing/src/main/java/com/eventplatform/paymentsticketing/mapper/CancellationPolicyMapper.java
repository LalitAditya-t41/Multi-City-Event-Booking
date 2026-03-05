package com.eventplatform.paymentsticketing.mapper;

import com.eventplatform.paymentsticketing.api.dto.response.CancellationPolicyResponse;
import com.eventplatform.paymentsticketing.domain.CancellationPolicy;
import com.eventplatform.paymentsticketing.domain.CancellationPolicyTier;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CancellationPolicyMapper {

  CancellationPolicyResponse toResponse(CancellationPolicy policy);

  CancellationPolicyResponse.TierResponse toTierResponse(CancellationPolicyTier tier);
}
