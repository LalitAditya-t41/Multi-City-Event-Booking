package com.eventplatform.promotions.mapper;

import com.eventplatform.promotions.api.dto.response.CouponResponse;
import com.eventplatform.promotions.domain.Coupon;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CouponMapper {

    default CouponResponse toResponse(Coupon coupon) {
        return new CouponResponse(
            coupon.getId(),
            coupon.getPromotion().getId(),
            coupon.getOrgId(),
            coupon.getCode(),
            coupon.getStatus(),
            coupon.getRedemptionCount(),
            coupon.getEbSyncStatus(),
            coupon.getEbDiscountId(),
            coupon.getEbQuantitySoldAtLastSync(),
            coupon.getLastEbSyncAt(),
            coupon.getCreatedAt()
        );
    }
}
