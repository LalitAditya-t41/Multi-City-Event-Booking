package com.eventplatform.paymentsticketing.api.controller;

import com.eventplatform.paymentsticketing.api.dto.response.TicketsByBookingResponse;
import com.eventplatform.paymentsticketing.service.ETicketService;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tickets")
public class ETicketController {

    private final ETicketService eTicketService;

    public ETicketController(ETicketService eTicketService) {
        this.eTicketService = eTicketService;
    }

    @GetMapping("/{bookingRef}")
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public TicketsByBookingResponse getTickets(Authentication authentication, @PathVariable String bookingRef) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return eTicketService.getTickets(user.userId(), bookingRef);
    }
}
