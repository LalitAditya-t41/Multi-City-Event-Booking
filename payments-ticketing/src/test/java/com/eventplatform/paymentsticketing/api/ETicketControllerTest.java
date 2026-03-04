package com.eventplatform.paymentsticketing.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.paymentsticketing.api.controller.ETicketController;
import com.eventplatform.paymentsticketing.api.dto.response.ETicketResponse;
import com.eventplatform.paymentsticketing.api.dto.response.TicketsByBookingResponse;
import com.eventplatform.paymentsticketing.domain.enums.ETicketStatus;
import com.eventplatform.paymentsticketing.exception.BookingNotFoundException;
import com.eventplatform.paymentsticketing.service.ETicketService;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.JwtAuthenticationFilter;
import com.eventplatform.shared.security.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ETicketController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ETicketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ETicketService eTicketService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUpFilterPassThrough() throws Exception {
        doAnswer(invocation -> {
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    void should_return_200_with_ticket_list_for_owner() throws Exception {
        when(eTicketService.getTickets(1L, "BK-20260304-001")).thenReturn(new TicketsByBookingResponse(
            "BK-20260304-001",
            List.of(new ETicketResponse("BK-20260304-001:1", 1L, "qr", "/tickets/BK-20260304-001/1.pdf", ETicketStatus.ACTIVE))
        ));

        mockMvc.perform(get("/api/v1/tickets/BK-20260304-001").with(authentication(userAuthentication())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tickets[0].ticketCode").value("BK-20260304-001:1"));
    }

    @Test
    void should_return_404_for_non_owner_ticket_lookup() throws Exception {
        when(eTicketService.getTickets(1L, "BK-UNKNOWN")).thenThrow(new BookingNotFoundException("BK-UNKNOWN"));

        mockMvc.perform(get("/api/v1/tickets/BK-UNKNOWN").with(authentication(userAuthentication())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_FOUND"));
    }

    private UsernamePasswordAuthenticationToken userAuthentication() {
        return new UsernamePasswordAuthenticationToken(
            new AuthenticatedUser(1L, "USER", null, null),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }
}
