package com.eventplatform.promotions.mapper;

import com.eventplatform.promotions.api.dto.response.PromotionResponse;
import com.eventplatform.promotions.domain.Promotion;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface PromotionMapper {

    default PromotionResponse toResponse(Promotion promotion) {
        return new PromotionResponse(
            promotion.getId(),
            promotion.getOrgId(),
            promotion.getName(),
            promotion.getDiscountType(),
            promotion.getDiscountValue(),
            promotion.getScope(),
            promotion.getEbEventId(),
            promotion.getMaxUsageLimit(),
            promotion.getPerUserCap(),
            promotion.getValidFrom(),
            promotion.getValidUntil(),
            promotion.getStatus(),
            promotion.getCreatedAt(),
            promotion.getUpdatedAt()
        );
    }
}
