package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.request.EbDiscountCreateRequest;
import com.eventplatform.shared.eventbrite.dto.response.EbDiscountResponse;
import java.util.List;

public interface EbDiscountSyncService {

    EbDiscountResponse createDiscount(String orgId, EbDiscountCreateRequest request);

    List<EbDiscountResponse> listDiscounts(String orgId);

    EbDiscountResponse getDiscount(String discountId);

    void deleteDiscount(String discountId);
}
