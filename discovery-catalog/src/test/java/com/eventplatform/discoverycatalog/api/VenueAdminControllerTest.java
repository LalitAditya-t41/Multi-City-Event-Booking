package com.eventplatform.discoverycatalog.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.discoverycatalog.api.controller.VenueAdminController;
import com.eventplatform.discoverycatalog.api.dto.request.CreateVenueRequest;
import com.eventplatform.discoverycatalog.api.dto.request.UpdateVenueRequest;
import com.eventplatform.discoverycatalog.api.dto.response.VenueResponse;
import com.eventplatform.discoverycatalog.domain.Venue;
import com.eventplatform.discoverycatalog.domain.enums.VenueSyncStatus;
import com.eventplatform.discoverycatalog.mapper.VenueMapper;
import com.eventplatform.discoverycatalog.service.CityCatalogService;
import com.eventplatform.discoverycatalog.service.EventCatalogService;
import com.eventplatform.discoverycatalog.service.VenueService;
import com.eventplatform.discoverycatalog.service.VenueCatalogService;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.security.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;

@WebMvcTest(VenueAdminController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@TestPropertySource(properties = "app.default-org-id=1")
class VenueAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private VenueService venueService;
    @MockBean
    private VenueMapper venueMapper;
    @MockBean
    private CityCatalogService cityCatalogService;
    @MockBean
    private EventCatalogService eventCatalogService;
    @MockBean
    private VenueCatalogService venueCatalogService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void should_return_201_when_venue_created_and_EB_synced() throws Exception {
        Venue venue = baseVenue(VenueSyncStatus.SYNCED);
        when(venueService.createVenue(eq(1L), any())).thenReturn(venue);
        when(venueMapper.toResponse(venue)).thenReturn(baseResponse(VenueSyncStatus.SYNCED));

        mockMvc.perform(post("/api/v1/admin/catalog/venues")
                .queryParam("orgId", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(baseCreateRequest())))
            .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void should_return_202_when_venue_created_but_EB_sync_failed() throws Exception {
        Venue venue = baseVenue(VenueSyncStatus.PENDING_SYNC);
        when(venueService.createVenue(eq(1L), any())).thenReturn(venue);
        when(venueMapper.toResponse(venue)).thenReturn(baseResponse(VenueSyncStatus.PENDING_SYNC));

        mockMvc.perform(post("/api/v1/admin/catalog/venues")
                .queryParam("orgId", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(baseCreateRequest())))
            .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void should_return_400_when_name_blank() throws Exception {
        CreateVenueRequest request = new CreateVenueRequest(
            "",
            "Addr1",
            null,
            3L,
            "IN",
            "123",
            "12.9",
            "77.6",
            100,
            SeatingMode.GA
        );

        mockMvc.perform(post("/api/v1/admin/catalog/venues")
                .queryParam("orgId", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "USER")
    void should_return_403_when_user_not_ADMIN() throws Exception {
        mockMvc.perform(post("/api/v1/admin/catalog/venues")
                .queryParam("orgId", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(baseCreateRequest())))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void should_return_200_when_venue_updated() throws Exception {
        Venue venue = baseVenue(VenueSyncStatus.SYNCED);
        when(venueService.updateVenue(eq(1L), eq(10L), any())).thenReturn(venue);
        when(venueMapper.toResponse(venue)).thenReturn(baseResponse(VenueSyncStatus.SYNCED));

        mockMvc.perform(put("/api/v1/admin/catalog/venues/10")
                .queryParam("orgId", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(baseUpdateRequest())))
            .andExpect(status().isOk());
    }

    private CreateVenueRequest baseCreateRequest() {
        return new CreateVenueRequest(
            "Venue",
            "Addr1",
            null,
            3L,
            "IN",
            "123",
            "12.9",
            "77.6",
            100,
            SeatingMode.GA
        );
    }

    private UpdateVenueRequest baseUpdateRequest() {
        return new UpdateVenueRequest(
            "Venue",
            "Addr1",
            null,
            3L,
            "IN",
            "123",
            "12.9",
            "77.6",
            120,
            SeatingMode.GA
        );
    }

    private Venue baseVenue(VenueSyncStatus status) {
        Venue venue = new Venue(1L, 3L, "Venue", "Addr", "123", "12.9", "77.6", 100, SeatingMode.GA);
        if (status == VenueSyncStatus.SYNCED) {
            venue.markSynced("eb-1");
        } else if (status == VenueSyncStatus.DRIFT_FLAGGED) {
            venue.markDrift("drift");
        } else {
            venue.markSyncFailed("fail");
        }
        return venue;
    }

    private VenueResponse baseResponse(VenueSyncStatus status) {
        return new VenueResponse(
            10L,
            3L,
            "eb-1",
            "Venue",
            "Addr",
            "123",
            "12.9",
            "77.6",
            100,
            SeatingMode.GA,
            status,
            null,
            null,
            Instant.now()
        );
    }
}
