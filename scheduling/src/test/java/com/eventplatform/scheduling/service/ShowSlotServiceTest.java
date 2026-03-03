package com.eventplatform.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.scheduling.api.dto.request.CreateShowSlotRequest;
import com.eventplatform.scheduling.api.dto.request.PricingTierRequest;
import com.eventplatform.scheduling.api.dto.request.UpdateShowSlotRequest;
import com.eventplatform.scheduling.domain.ShowSlot;
import com.eventplatform.scheduling.domain.ShowSlotPricingTier;
import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;
import com.eventplatform.scheduling.domain.enums.TierType;
import com.eventplatform.scheduling.exception.SchedulingNotFoundException;
import com.eventplatform.scheduling.exception.SlotConflictException;
import com.eventplatform.scheduling.mapper.ShowSlotMapper;
import com.eventplatform.scheduling.repository.ShowSlotOccurrenceRepository;
import com.eventplatform.scheduling.repository.ShowSlotRepository;
import com.eventplatform.scheduling.service.client.CatalogVenueResponse;
import com.eventplatform.scheduling.service.client.VenueCatalogClient;
import com.eventplatform.scheduling.statemachine.ShowSlotEvent;
import com.eventplatform.scheduling.statemachine.ShowSlotStateMachineService;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.common.event.published.ShowSlotActivatedEvent;
import com.eventplatform.shared.common.event.published.SlotDraftCreatedEvent;
import com.eventplatform.shared.common.event.published.SlotSyncFailedEvent;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import com.eventplatform.shared.common.exception.ValidationException;
import com.eventplatform.shared.eventbrite.dto.EbEventDto;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.eventplatform.shared.eventbrite.service.EbCapacityService;
import com.eventplatform.shared.eventbrite.service.EbEventSyncService;
import com.eventplatform.shared.eventbrite.service.EbScheduleService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ShowSlotServiceTest {

    @Mock
    private ShowSlotRepository showSlotRepository;
    @Mock
    private ShowSlotOccurrenceRepository occurrenceRepository;
    @Mock
    private ShowSlotMapper showSlotMapper;
    @Mock
    private VenueCatalogClient venueCatalogClient;
    @Mock
    private ConflictDetectionService conflictDetectionService;
    @Mock
    private EbEventSyncService ebEventSyncService;
    @Mock
    private EbScheduleService ebScheduleService;
    @Mock
    private EbCapacityService ebCapacityService;
    @Mock
    private ShowSlotStateMachineService stateMachineService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ShowSlotService showSlotService;

    @Test
    void should_create_slot_in_DRAFT_status_when_venue_is_synced_and_no_conflict() {
        CreateShowSlotRequest request = baseCreateRequest(SeatingMode.GA, null);
        CatalogVenueResponse venue = new CatalogVenueResponse(10L, 20L, "eb-1", "Venue", "Addr", 100, SeatingMode.GA);
        ShowSlot slot = baseSlot();
        List<ShowSlotPricingTier> tiers = List.of(
            new ShowSlotPricingTier("General", new Money(BigDecimal.ZERO, "INR"), 10, TierType.FREE)
        );

        when(venueCatalogClient.getVenue(request.venueId())).thenReturn(venue);
        when(showSlotMapper.toEntity(eq(1L), eq(request.venueId()), eq(venue.cityId()), eq(request))).thenReturn(slot);
        when(showSlotMapper.toPricingTiers(request.pricingTiers())).thenReturn(tiers);
        when(showSlotRepository.save(slot)).thenReturn(slot);

        ShowSlot result = showSlotService.createSlot(1L, request);

        assertThat(result.getStatus()).isEqualTo(ShowSlotStatus.DRAFT);
        verify(showSlotRepository).save(slot);
    }

    @Test
    void should_throw_SchedulingNotFoundException_when_venue_not_found_via_rest_call() {
        CreateShowSlotRequest request = baseCreateRequest(SeatingMode.GA, null);
        when(venueCatalogClient.getVenue(request.venueId()))
            .thenThrow(new SchedulingNotFoundException("Venue not found", "VENUE_NOT_FOUND"));

        assertThatThrownBy(() -> showSlotService.createSlot(1L, request))
            .isInstanceOf(SchedulingNotFoundException.class);
    }

    @Test
    void should_throw_BusinessRuleException_when_venue_sync_status_is_PENDING_SYNC() {
        CreateShowSlotRequest request = baseCreateRequest(SeatingMode.GA, null);
        CatalogVenueResponse venue = new CatalogVenueResponse(10L, 20L, null, "Venue", "Addr", 100, SeatingMode.GA);
        when(venueCatalogClient.getVenue(request.venueId())).thenReturn(venue);

        assertThatThrownBy(() -> showSlotService.createSlot(1L, request))
            .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void should_throw_SlotConflictException_with_alternatives_when_overlap_detected() {
        CreateShowSlotRequest request = baseCreateRequest(SeatingMode.GA, null);
        CatalogVenueResponse venue = new CatalogVenueResponse(10L, 20L, "eb-1", "Venue", "Addr", 100, SeatingMode.GA);
        when(venueCatalogClient.getVenue(request.venueId())).thenReturn(venue);
        doAnswer(invocation -> {
            throw new SlotConflictException("Conflict", null);
        }).when(conflictDetectionService).validateOrThrow(eq(1L), eq(venue), eq(request.startTime()), eq(request.endTime()));

        assertThatThrownBy(() -> showSlotService.createSlot(1L, request))
            .isInstanceOf(SlotConflictException.class);
    }

    @Test
    void should_throw_ValidationException_when_RESERVED_slot_has_no_sourceSeatMapId() {
        CreateShowSlotRequest request = baseCreateRequest(SeatingMode.RESERVED, null);
        CatalogVenueResponse venue = new CatalogVenueResponse(10L, 20L, "eb-1", "Venue", "Addr", 100, SeatingMode.RESERVED);
        when(venueCatalogClient.getVenue(request.venueId())).thenReturn(venue);

        assertThatThrownBy(() -> showSlotService.createSlot(1L, request))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void should_transition_to_PENDING_SYNC_and_call_createDraft_when_slot_is_DRAFT() {
        ShowSlot slot = baseSlot();
        slot.addPricingTier(new ShowSlotPricingTier("General", new Money(BigDecimal.ZERO, "INR"), 10, TierType.FREE));
        when(showSlotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(ebEventSyncService.createDraft(eq(1L), any())).thenReturn(new EbEventDto("eb-1", null, null, null, null, null, null, null, null, Instant.now()));

        ShowSlot result = showSlotService.submitSlot(1L, 1L);

        verify(stateMachineService).sendEvent(slot, ShowSlotEvent.SUBMIT);
        assertThat(result.getEbEventId()).isEqualTo("eb-1");
        ArgumentCaptor<SlotDraftCreatedEvent> captor = ArgumentCaptor.forClass(SlotDraftCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().ebEventId()).isEqualTo("eb-1");
    }

    @Test
    void should_increment_syncAttemptCount_and_not_publish_success_event_when_createDraft_fails() {
        ShowSlot slot = baseSlot();
        slot.addPricingTier(new ShowSlotPricingTier("General", new Money(BigDecimal.ZERO, "INR"), 10, TierType.FREE));
        when(showSlotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(ebEventSyncService.createDraft(eq(1L), any())).thenThrow(new EbIntegrationException("boom"));

        assertThatThrownBy(() -> showSlotService.submitSlot(1L, 1L))
            .isInstanceOf(EbIntegrationException.class);

        assertThat(slot.getSyncAttemptCount()).isEqualTo(1);
        verify(eventPublisher, never()).publishEvent(any(SlotDraftCreatedEvent.class));
        verify(eventPublisher).publishEvent(any(SlotSyncFailedEvent.class));
    }

    @Test
    void should_transition_to_ACTIVE_when_publishEvent_succeeds() {
        ShowSlot slot = baseSlot();
        slot.markPendingSync();
        slot.setEbEventId("eb-1");
        when(showSlotRepository.findById(1L)).thenReturn(Optional.of(slot));
        doAnswer(invocation -> {
            slot.markActive();
            return null;
        }).when(stateMachineService).sendEvent(slot, ShowSlotEvent.EB_PUBLISHED);

        showSlotService.onTicketSyncComplete(new com.eventplatform.shared.common.event.published.TicketSyncCompletedEvent(1L, "eb-1"));

        assertThat(slot.getStatus()).isEqualTo(ShowSlotStatus.ACTIVE);
        verify(eventPublisher).publishEvent(any(ShowSlotActivatedEvent.class));
    }

    @Test
    void should_increment_syncAttemptCount_and_stay_PENDING_SYNC_when_publishEvent_fails() {
        ShowSlot slot = baseSlot();
        slot.markPendingSync();
        slot.setEbEventId("eb-1");
        when(showSlotRepository.findById(1L)).thenReturn(Optional.of(slot));
        doAnswer(invocation -> { throw new EbIntegrationException("boom"); })
            .when(ebEventSyncService).publishEvent(eq(1L), eq("eb-1"));

        assertThatThrownBy(() -> showSlotService.onTicketSyncComplete(
            new com.eventplatform.shared.common.event.published.TicketSyncCompletedEvent(1L, "eb-1")))
            .isInstanceOf(EbIntegrationException.class);

        assertThat(slot.getSyncAttemptCount()).isEqualTo(1);
        verify(eventPublisher).publishEvent(any(SlotSyncFailedEvent.class));
    }

    @Test
    void should_persist_update_and_call_updateEvent_when_slot_is_ACTIVE() {
        ShowSlot slot = baseSlot();
        slot.markActive();
        slot.setEbEventId("eb-1");
        when(showSlotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(showSlotRepository.save(slot)).thenReturn(slot);

        ShowSlotUpdateResult result = showSlotService.updateSlot(1L, 1L, new UpdateShowSlotRequest("New", null, null, null, null, null));

        assertThat(result.ebSyncFailed()).isFalse();
        verify(ebEventSyncService).updateEvent(eq(1L), eq("eb-1"), any());
    }

    @Test
    void should_persist_internal_update_and_return_207_when_EB_update_fails() {
        ShowSlot slot = baseSlot();
        slot.markActive();
        slot.setEbEventId("eb-1");
        when(showSlotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(showSlotRepository.save(slot)).thenReturn(slot);
        when(ebEventSyncService.updateEvent(eq(1L), eq("eb-1"), any()))
            .thenThrow(new EbIntegrationException("boom"));

        ShowSlotUpdateResult result = showSlotService.updateSlot(1L, 1L, new UpdateShowSlotRequest("New", null, null, null, null, null));

        assertThat(result.ebSyncFailed()).isTrue();
        assertThat(result.slot().getLastSyncError()).isNotNull();
    }

    @Test
    void should_throw_BusinessRuleException_when_updating_PENDING_SYNC_slot() {
        ShowSlot slot = baseSlot();
        slot.markPendingSync();
        when(showSlotRepository.findById(1L)).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> showSlotService.updateSlot(1L, 1L, new UpdateShowSlotRequest("New", null, null, null, null, null)))
            .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void should_transition_to_CANCELLED_and_call_cancelEvent_when_slot_is_ACTIVE() {
        ShowSlot slot = baseSlot();
        slot.markActive();
        slot.setEbEventId("eb-1");
        when(showSlotRepository.findById(1L)).thenReturn(Optional.of(slot));
        doAnswer(invocation -> {
            slot.markCancelled();
            return null;
        }).when(stateMachineService).sendEvent(slot, ShowSlotEvent.CANCEL);

        boolean result = showSlotService.cancelSlot(1L, 1L);

        assertThat(result).isTrue();
        assertThat(slot.getStatus()).isEqualTo(ShowSlotStatus.CANCELLED);
        verify(ebEventSyncService).cancelEvent(1L, "eb-1");
    }

    @Test
    void should_transition_to_CANCELLED_even_when_cancelEvent_fails() {
        ShowSlot slot = baseSlot();
        slot.markActive();
        slot.setEbEventId("eb-1");
        when(showSlotRepository.findById(1L)).thenReturn(Optional.of(slot));
        doAnswer(invocation -> {
            slot.markCancelled();
            return null;
        }).when(stateMachineService).sendEvent(slot, ShowSlotEvent.CANCEL);
        doAnswer(invocation -> { throw new EbIntegrationException("boom"); })
            .when(ebEventSyncService).cancelEvent(1L, "eb-1");

        boolean result = showSlotService.cancelSlot(1L, 1L);

        assertThat(result).isFalse();
        assertThat(slot.getStatus()).isEqualTo(ShowSlotStatus.CANCELLED);
    }

    private CreateShowSlotRequest baseCreateRequest(SeatingMode seatingMode, String seatMapId) {
        return new CreateShowSlotRequest(
            10L,
            "Show",
            "Desc",
            ZonedDateTime.now().plusDays(1),
            ZonedDateTime.now().plusDays(1).plusHours(2),
            seatingMode,
            100,
            List.of(new PricingTierRequest("Free", BigDecimal.ZERO, "INR", 10, TierType.FREE)),
            false,
            null,
            seatMapId
        );
    }

    private ShowSlot baseSlot() {
        return new ShowSlot(
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
    }
}
