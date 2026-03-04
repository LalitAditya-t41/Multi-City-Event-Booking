package com.eventplatform.promotions.mapper;

import com.eventplatform.promotions.domain.Coupon;
import com.eventplatform.promotions.domain.Promotion;
import com.eventplatform.promotions.domain.enums.DiscountType;
import com.eventplatform.shared.eventbrite.dto.request.EbDiscountCreateRequest;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface EbDiscountSyncPayloadMapper {

    default EbDiscountCreateRequest toRequest(Coupon coupon, Promotion promotion) {
        Double percentOff = null;
        Double amountOff = null;
        if (promotion.getDiscountType() == DiscountType.PERCENT_OFF) {
            percentOff = promotion.getDiscountValue().doubleValue();
        } else {
            amountOff = promotion.getDiscountValue().doubleValue();
        }
        return new EbDiscountCreateRequest(
            coupon.getCode(),
            promotion.getDiscountType() == DiscountType.PERCENT_OFF ? "percent" : "amount",
            percentOff,
            amountOff,
            promotion.getValidFrom().toString(),
            promotion.getValidUntil().toString(),
            promotion.getMaxUsageLimit(),
            promotion.getEbEventId()
        );
    }
}
