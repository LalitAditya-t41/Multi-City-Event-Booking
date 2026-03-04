package com.eventplatform.paymentsticketing.exception;

import com.eventplatform.shared.common.exception.BaseException;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class DuplicateOrgPolicyException extends BaseException {

    public DuplicateOrgPolicyException(Long orgId) {
        super(
            "Cancellation policy already exists for orgId=" + orgId,
            "DUPLICATE_ORG_POLICY",
            HttpStatus.CONFLICT,
            Map.of("orgId", orgId)
        );
    }
}
