package com.eventplatform.paymentsticketing.mapper;

import com.eventplatform.paymentsticketing.api.dto.response.ETicketResponse;
import com.eventplatform.paymentsticketing.domain.ETicket;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ETicketMapper {

    @Mapping(target = "ticketCode", ignore = true)
    ETicketResponse toResponse(ETicket ticket);
}
