package com.eventplatform.paymentsticketing.mapper;

import com.eventplatform.paymentsticketing.api.dto.response.BookingResponse;
import com.eventplatform.paymentsticketing.api.dto.response.BookingSummaryResponse;
import com.eventplatform.paymentsticketing.domain.Booking;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface BookingMapper {

  @Mapping(target = "items", ignore = true)
  @Mapping(source = "createdAt", target = "createdAt")
  BookingResponse toResponse(Booking booking);

  @Mapping(source = "createdAt", target = "createdAt")
  BookingSummaryResponse toSummaryResponse(Booking booking);
}
