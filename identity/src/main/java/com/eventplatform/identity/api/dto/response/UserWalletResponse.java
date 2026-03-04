package com.eventplatform.identity.api.dto.response;

import java.math.BigDecimal;

public record UserWalletResponse(BigDecimal balance, String currency) {
}
