package com.eventplatform.scheduling.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.scheduling.api.controller.ShowSlotController;
import com.eventplatform.scheduling.api.dto.request.CreateShowSlotRequest;
import com.eventplatform.scheduling.api.dto.request.PricingTierRequest;
import com.eventplatform.scheduling.api.dto.request.UpdateShowSlotRequest;
import com.eventplatform.scheduling.api.dto.response.ShowSlotPricingTierResponse;
import com.eventplatform.scheduling.api.dto.response.ShowSlotResponse;
import com.eventplatform.scheduling.domain.ShowSlot;
import com.eventplatform.scheduling.domain.ShowSlotPricingTier;
import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;
import com.eventplatform.scheduling.domain.enums.TierType;
import com.eventplatform.scheduling.exception.SlotConflictException;
import com.eventplatform.scheduling.mapper.ShowSlotMapper;
import com.eventplatform.scheduling.service.ShowSlotService;
import com.eventplatform.scheduling.service.ShowSlotUpdateResult;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.eventplatform.shared.security.JwtAuthenticationFilter;
import com.eventplatform.shared.security.JwtTokenProvider;
import com.eventplatform.shared.security.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ContextConfiguration;

@WebMvcTest(ShowSlotController.class)
@ContextConfiguration(classes = com.eventplatform.scheduling.SchedulingTestApplication.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, ShowSlotControllerTest.MockBeans.class})
class ShowSlotControllerTest {

    @TestConfiguration
    static class MockBeans {
        @Bean
        ShowSlotService showSlotService() { return mock(ShowSlotService.class); }
        @Bean
        ShowSlotMapper showSlotMapper() { return mock(ShowSlotMapper.class); }
        @Bean
        JwtTokenProvider jwtTokenProvider() { return mock(JwtTokenProvider.class); }
        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
            return new JwtAuthenticationFilter(jwtTokenProvider);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ShowSlotService showSlotService;
    @Autowired
    private ShowSlotMapper showSlotMapper;

    @Test
    @WithMockUser(roles = "ADMIN")
    void should_return_201_when_slot_created() throws Exception {
        ShowSlot slot = baseSlot();
        ShowSlotResponse response = baseResponse();
        when(showSlotService.createSlot(eq(1L), any())).thenReturn(slot);
        when(showSlotMapper.toResponse(slot)).thenReturn(response);

        mockMvc.perform(post("/api/v1/scheduling/slots")
                .queryParam("orgId", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(baseCreateRequest())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void should_return_400_when_endTime_is_not_after_startTime() throws Exception {
        CreateShowSlotRequest request = new CreateShowSlotRequest(
            10L,
            "Show",
            "Desc",
            ZonedDateTime.now().plusDays(1),
            ZonedDateTime.now(),
            SeatingMode.GA,
            100,
            List.of(new PricingTierRequest("Free", BigDecimal.ZERO, "INR", 10, TierType.FREE)),
            false,
            null,
            null
        );

        mockMvc.perform(post("/api/v1/scheduling/slots")
                .queryParam("orgId", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void should_return_422_when_conflict_detected() throws Exception {
        when(showSlotService.createSlot(eq(1L), any()))
            .thenThrow(new SlotConflictException("Conflict", null));

        mockMvc.perform(post("/api/v1/scheduling/slots")
                .queryParam("orgId", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(baseCreateRequest())))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("SLOT_CONFLICT"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void should_return_502_when_createDraft_fails() throws Exception {
        when(showSlotService.submitSlot(eq(1L), eq(1L)))
            .thenThrow(new EbIntegrationException("boom"));

        mockMvc.perform(post("/api/v1/scheduling/slots/1/submit")
                .queryParam("orgId", "1"))
            .andExpect(status().isBadGateway());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void should_return_207_when_internal_update_succeeds_but_EB_sync_fails() throws Exception {
        ShowSlot slot = baseSlot();
        ShowSlotResponse response = baseResponse();
        when(showSlotService.updateSlot(eq(1L), eq(1L), any()))
            .thenReturn(new ShowSlotUpdateResult(slot, true));
        when(showSlotMapper.toResponse(slot)).thenReturn(response);

        mockMvc.perform(put("/api/v1/scheduling/slots/1")
                .queryParam("orgId", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateShowSlotRequest("New", null, null, null, null, null))))
            .andExpect(status().isMultiStatus());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void should_return_422_when_updating_PENDING_SYNC_slot() throws Exception {
        when(showSlotService.updateSlot(eq(1L), eq(1L), any()))
            .thenThrow(new BusinessRuleException("Slot cannot be edited", "SLOT_PENDING_SYNC"));

        mockMvc.perform(put("/api/v1/scheduling/slots/1")
                .queryParam("orgId", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateShowSlotRequest("New", null, null, null, null, null))))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void should_return_401_when_no_auth_token_provided() throws Exception {
        mockMvc.perform(post("/api/v1/scheduling/slots")
                .queryParam("orgId", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(baseCreateRequest())))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void should_return_403_when_user_not_ADMIN() throws Exception {
        mockMvc.perform(post("/api/v1/scheduling/slots")
                .queryParam("orgId", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(baseCreateRequest())))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser
    void should_return_200_with_pricing_tiers_for_authenticated_user() throws Exception {
        ShowSlotPricingTier tier = new ShowSlotPricingTier("General",
            new com.eventplatform.shared.common.domain.Money(BigDecimal.ZERO, "INR"), 50,
            com.eventplatform.scheduling.domain.enums.TierType.FREE);
        ShowSlotPricingTierResponse response = new ShowSlotPricingTierResponse(
            1L, "General", BigDecimal.ZERO, "INR", 50, TierType.FREE, null, null, null, null);
        when(showSlotService.getPricingTiers(1L)).thenReturn(List.of(tier));
        when(showSlotMapper.toPricingTierResponse(tier)).thenReturn(response);

        mockMvc.perform(get("/api/v1/scheduling/slots/1/pricing-tiers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("General"))
            .andExpect(jsonPath("$[0].tierType").value("FREE"));
    }

    @Test
    void should_return_401_when_unauthenticated_requests_pricing_tiers() throws Exception {
        mockMvc.perform(get("/api/v1/scheduling/slots/1/pricing-tiers"))
            .andExpect(status().isUnauthorized());
    }

    private CreateShowSlotRequest baseCreateRequest() {
        return new CreateShowSlotRequest(
            10L,
            "Show",
            "Desc",
            ZonedDateTime.now().plusDays(1),
            ZonedDateTime.now().plusDays(1).plusHours(2),
            SeatingMode.GA,
            100,
            List.of(new PricingTierRequest("Free", BigDecimal.ZERO, "INR", 10, TierType.FREE)),
            false,
            null,
            null
        );
    }

    private ShowSlot baseSlot() {
        ShowSlot slot = new ShowSlot(
            1L,
            10L,
            20L,
            "Show",
            "Desc",
            ZonedDateTime.now().plusDays(1),
            ZonedDateTime.now().plusDays(1).plusHours(2),
            SeatingMode.GA,
            100,
            false,
            null,
            null
        );
        slot.markPendingSync();
        slot.markActive();
        return slot;
    }

    private ShowSlotResponse baseResponse() {
        return new ShowSlotResponse(
            1L,
            10L,
            20L,
            "Show",
            "Desc",
            ZonedDateTime.now().plusDays(1),
            ZonedDateTime.now().plusDays(1).plusHours(2),
            SeatingMode.GA,
            100,
            ShowSlotStatus.DRAFT,
            false,
            "eb-1",
            null,
            0,
            null,
            null,
            List.of(),
            ZonedDateTime.now().toInstant(),
            ZonedDateTime.now().toInstant()
        );
    }
}
