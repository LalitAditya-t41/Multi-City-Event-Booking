package com.eventplatform.bookinginventory.mapper;

import com.eventplatform.bookinginventory.api.dto.response.AvailableSeatResponse;
import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.shared.common.domain.Money;
import java.math.BigDecimal;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface SeatMapper {

    default AvailableSeatResponse toAvailableSeatResponse(Seat seat, String tierName, BigDecimal amount, String currency) {
        return new AvailableSeatResponse(
            seat.getId(),
            seat.getSeatNumber(),
            seat.getRowLabel(),
            seat.getSection(),
            seat.getPricingTierId(),
            tierName,
            new Money(amount, currency),
            seat.getLockState()
        );
    }
}
