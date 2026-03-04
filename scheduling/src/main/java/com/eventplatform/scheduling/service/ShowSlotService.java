package com.eventplatform.scheduling.service;

import com.eventplatform.scheduling.api.dto.request.CreateShowSlotRequest;
import com.eventplatform.scheduling.api.dto.request.UpdateShowSlotRequest;
import com.eventplatform.scheduling.domain.ShowSlot;
import com.eventplatform.scheduling.domain.ShowSlotOccurrence;
import com.eventplatform.scheduling.domain.ShowSlotPricingTier;
import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;
import com.eventplatform.scheduling.exception.SchedulingNotFoundException;
import com.eventplatform.scheduling.mapper.ShowSlotMapper;
import com.eventplatform.scheduling.repository.ShowSlotOccurrenceRepository;
import com.eventplatform.scheduling.repository.ShowSlotRepository;
import com.eventplatform.scheduling.service.client.CatalogVenueResponse;
import com.eventplatform.scheduling.service.client.VenueCatalogClient;
import com.eventplatform.scheduling.statemachine.ShowSlotEvent;
import com.eventplatform.scheduling.statemachine.ShowSlotStateMachineService;
import com.eventplatform.shared.common.event.published.ShowSlotActivatedEvent;
import com.eventplatform.shared.common.event.published.ShowSlotCancelledEvent;
import com.eventplatform.shared.common.event.published.SlotDraftCreatedEvent;
import com.eventplatform.shared.common.event.published.SlotSyncFailedEvent;
import com.eventplatform.shared.common.event.published.TicketSyncCompletedEvent;
import com.eventplatform.shared.common.event.published.TicketSyncFailedEvent;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import com.eventplatform.shared.common.exception.IntegrationException;
import com.eventplatform.shared.common.exception.ValidationException;
import com.eventplatform.shared.eventbrite.dto.request.EbEventCreateRequest;
import com.eventplatform.shared.eventbrite.dto.response.EbEventDto;
import com.eventplatform.shared.eventbrite.dto.request.EbEventUpdateRequest;
import com.eventplatform.shared.eventbrite.dto.response.EbScheduleResponse;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.eventplatform.shared.eventbrite.service.EbCapacityService;
import com.eventplatform.shared.eventbrite.service.EbEventSyncService;
import com.eventplatform.shared.eventbrite.service.EbScheduleService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowSlotService {

    private final ShowSlotRepository showSlotRepository;
    private final ShowSlotOccurrenceRepository occurrenceRepository;
    private final ShowSlotMapper showSlotMapper;
    private final VenueCatalogClient venueCatalogClient;
    private final ConflictDetectionService conflictDetectionService;
    private final EbEventSyncService ebEventSyncService;
    private final EbScheduleService ebScheduleService;
    private final EbCapacityService ebCapacityService;
    private final ShowSlotStateMachineService stateMachineService;
    private final ApplicationEventPublisher eventPublisher;

    public ShowSlotService(
        ShowSlotRepository showSlotRepository,
        ShowSlotOccurrenceRepository occurrenceRepository,
        ShowSlotMapper showSlotMapper,
        VenueCatalogClient venueCatalogClient,
        ConflictDetectionService conflictDetectionService,
        EbEventSyncService ebEventSyncService,
        EbScheduleService ebScheduleService,
        EbCapacityService ebCapacityService,
        ShowSlotStateMachineService stateMachineService,
        ApplicationEventPublisher eventPublisher
    ) {
        this.showSlotRepository = showSlotRepository;
        this.occurrenceRepository = occurrenceRepository;
        this.showSlotMapper = showSlotMapper;
        this.venueCatalogClient = venueCatalogClient;
        this.conflictDetectionService = conflictDetectionService;
        this.ebEventSyncService = ebEventSyncService;
        this.ebScheduleService = ebScheduleService;
        this.ebCapacityService = ebCapacityService;
        this.stateMachineService = stateMachineService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ShowSlot createSlot(Long organizationId, CreateShowSlotRequest request) {
        CatalogVenueResponse venue = venueCatalogClient.getVenue(request.venueId());
        validateCreateRequest(venue, request);
        conflictDetectionService.validateOrThrow(organizationId, venue, request.startTime(), request.endTime());

        ShowSlot slot = showSlotMapper.toEntity(organizationId, request.venueId(), venue.cityId(), request);
        List<ShowSlotPricingTier> tiers = showSlotMapper.toPricingTiers(request.pricingTiers());
        tiers.forEach(slot::addPricingTier);

        return showSlotRepository.save(slot);
    }

    @Transactional
    public ShowSlot submitSlot(Long organizationId, Long slotId) {
        ShowSlot slot = getSlotOrThrow(slotId);
        stateMachineService.sendEvent(slot, ShowSlotEvent.SUBMIT);

        CatalogVenueResponse venue = venueCatalogClient.getVenue(slot.getVenueId());
        if (venue.eventbriteVenueId() == null || venue.eventbriteVenueId().isBlank()) {
            throw new com.eventplatform.shared.common.exception.BusinessRuleException(
                "Cannot submit slot: venue not yet synced to Eventbrite", "VENUE_NOT_SYNCED");
        }

        try {
            EbEventDto created = ebEventSyncService.createDraft(organizationId, toEbCreateRequest(slot, venue));
            slot.setEbEventId(created.id());
            slot.markAttempted();
            showSlotRepository.save(slot);
            eventPublisher.publishEvent(new SlotDraftCreatedEvent(
                slot.getId(),
                slot.getEbEventId(),
                slot.getPricingTiers().stream().map(ShowSlotPricingTier::getId).toList(),
                slot.getSeatingMode(),
                organizationId,
                slot.getSourceSeatMapId()
            ));
            return slot;
        } catch (EbIntegrationException ex) {
            slot.recordSyncFailure(ex.getMessage());
            showSlotRepository.save(slot);
            eventPublisher.publishEvent(new SlotSyncFailedEvent(slot.getId(), ex.getMessage()));
            throw ex;
        } catch (Exception ex) {
            slot.recordSyncFailure(ex.getMessage());
            showSlotRepository.save(slot);
            eventPublisher.publishEvent(new SlotSyncFailedEvent(slot.getId(), ex.getMessage()));
            throw new IntegrationException("Failed to create Eventbrite draft", "EB_DRAFT_FAILED");
        }
    }

    @Transactional
    public void onTicketSyncComplete(TicketSyncCompletedEvent event) {
        ShowSlot slot = getSlotOrThrow(event.slotId());
        Long organizationId = slot.getOrganizationId();
        try {
            ebEventSyncService.publishEvent(organizationId, event.ebEventId());
            stateMachineService.sendEvent(slot, ShowSlotEvent.EB_PUBLISHED);
            showSlotRepository.save(slot);
            eventPublisher.publishEvent(new ShowSlotActivatedEvent(
                slot.getId(),
                slot.getEbEventId(),
                organizationId,
                slot.getVenueId(),
                slot.getCityId()
            ));
            if (slot.isRecurring() && slot.getRecurrenceRule() != null) {
                EbScheduleResponse scheduleResponse = ebScheduleService.createSchedule(organizationId, slot.getEbEventId(), slot.getRecurrenceRule());
                slot.setEbSeriesId(scheduleResponse.seriesId());
                if (scheduleResponse.occurrences() != null) {
                    int index = 1;
                    for (var occurrence : scheduleResponse.occurrences()) {
                        ShowSlotOccurrence child = new ShowSlotOccurrence(index++, occurrence.startTime(), occurrence.endTime());
                        child.setEbEventId(occurrence.eventId());
                        slot.addOccurrence(child);
                    }
                }
                showSlotRepository.save(slot);
            }
        } catch (EbIntegrationException ex) {
            slot.recordSyncFailure(ex.getMessage());
            showSlotRepository.save(slot);
            eventPublisher.publishEvent(new SlotSyncFailedEvent(slot.getId(), ex.getMessage()));
            throw ex;
        }
    }

    @Transactional
    public void onTicketSyncFailed(TicketSyncFailedEvent event) {
        ShowSlot slot = getSlotOrThrow(event.slotId());
        slot.recordSyncFailure(event.reason());
        showSlotRepository.save(slot);
        eventPublisher.publishEvent(new SlotSyncFailedEvent(slot.getId(), event.reason()));
    }

    @Transactional
    public ShowSlotUpdateResult updateSlot(Long organizationId, Long slotId, UpdateShowSlotRequest request) {
        ShowSlot slot = getSlotOrThrow(slotId);
        if (slot.getStatus() == ShowSlotStatus.CANCELLED) {
            throw new BusinessRuleException("Cannot update a cancelled slot", "SLOT_CANCELLED");
        }
        if (slot.getStatus() == ShowSlotStatus.PENDING_SYNC) {
            throw new BusinessRuleException("Slot cannot be edited while sync is in progress (PENDING_SYNC)", "SLOT_PENDING_SYNC");
        }

        if (request.startTime() != null || request.endTime() != null) {
            CatalogVenueResponse venue = venueCatalogClient.getVenue(slot.getVenueId());
            conflictDetectionService.validateOrThrow(organizationId, venue, request.startTime(), request.endTime(), slot.getId());
        }

        Integer previousCapacity = slot.getCapacity();
        slot.updateDetails(request.title(), request.description(), request.startTime(), request.endTime(), request.capacity());

        if (request.pricingTiers() != null) {
            validatePricingTiers(request.pricingTiers());
            slot.clearPricingTiers();
            showSlotMapper.toPricingTiers(request.pricingTiers()).forEach(slot::addPricingTier);
        }

        slot = showSlotRepository.save(slot);

        boolean ebSyncFailed = false;
        if (slot.getStatus() == ShowSlotStatus.ACTIVE) {
            try {
                ebEventSyncService.updateEvent(organizationId, slot.getEbEventId(), toEbUpdateRequest(slot));
                if (request.capacity() != null && !request.capacity().equals(previousCapacity)) {
                    ebCapacityService.updateCapacityTier(slot.getEbEventId(), request.capacity());
                }
            } catch (EbIntegrationException ex) {
                slot.recordSyncFailure(ex.getMessage());
                showSlotRepository.save(slot);
                ebSyncFailed = true;
            }
        }

        return new ShowSlotUpdateResult(slot, ebSyncFailed);
    }

    @Transactional
    public boolean cancelSlot(Long organizationId, Long slotId) {
        ShowSlot slot = getSlotOrThrow(slotId);
        if (slot.getStatus() == ShowSlotStatus.CANCELLED) {
            throw new BusinessRuleException("Slot already cancelled", "SLOT_ALREADY_CANCELLED");
        }
        stateMachineService.sendEvent(slot, ShowSlotEvent.CANCEL);
        showSlotRepository.save(slot);

        boolean ebCancelled = true;
        try {
            if (slot.getEbEventId() != null) {
                ebEventSyncService.cancelEvent(organizationId, slot.getEbEventId());
            }
        } catch (EbIntegrationException ex) {
            ebCancelled = false;
            slot.recordSyncFailure(ex.getMessage());
            showSlotRepository.save(slot);
        }
        eventPublisher.publishEvent(new ShowSlotCancelledEvent(slot.getId(), slot.getEbEventId(), organizationId, slot.getVenueId(), slot.getCityId()));
        return ebCancelled;
    }

    @Transactional
    public void retrySync(Long organizationId, Long slotId) {
        ShowSlot slot = getSlotOrThrow(slotId);
        if (slot.getStatus() != ShowSlotStatus.PENDING_SYNC) {
            throw new BusinessRuleException("Slot is not pending sync", "SLOT_NOT_PENDING");
        }
        if (slot.getEbEventId() == null) {
            submitSlot(organizationId, slotId);
            return;
        }
        eventPublisher.publishEvent(new SlotDraftCreatedEvent(
            slot.getId(),
            slot.getEbEventId(),
            slot.getPricingTiers().stream().map(ShowSlotPricingTier::getId).toList(),
            slot.getSeatingMode(),
            organizationId,
            slot.getSourceSeatMapId()
        ));
    }

    @Transactional(readOnly = true)
    public ShowSlot getSlot(Long slotId) {
        return getSlotOrThrow(slotId);
    }

    @Transactional(readOnly = true)
    public List<ShowSlotOccurrence> listOccurrences(Long slotId) {
        ShowSlot slot = getSlotOrThrow(slotId);
        if (!slot.isRecurring()) {
            throw new BusinessRuleException("Slot is not recurring", "SLOT_NOT_RECURRING");
        }
        return occurrenceRepository.findByParentSlotIdOrderByOccurrenceIndexAsc(slotId);
    }

    @Transactional(readOnly = true)
    public Page<ShowSlot> listSlots(Long organizationId, ShowSlotStatus status, Long venueId, Long cityId,
                                    java.time.ZonedDateTime startAfter, int page, int size) {
        Specification<ShowSlot> spec = (root, query, cb) -> cb.equal(root.get("organizationId"), organizationId);
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (venueId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("venueId"), venueId));
        }
        if (cityId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("cityId"), cityId));
        }
        if (startAfter != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThan(root.get("startTime"), startAfter));
        }
        return showSlotRepository.findAll(spec, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public Page<ShowSlot> listMismatches(Long organizationId, int page, int size) {
        return showSlotRepository.findByOrganizationIdAndStatusAndLastSyncErrorIsNotNull(
            organizationId,
            ShowSlotStatus.ACTIVE,
            PageRequest.of(page, size)
        );
    }

    private ShowSlot getSlotOrThrow(Long slotId) {
        return showSlotRepository.findById(slotId)
            .orElseThrow(() -> new SchedulingNotFoundException("Slot not found: " + slotId, "SLOT_NOT_FOUND"));
    }

    private EbEventCreateRequest toEbCreateRequest(ShowSlot slot, CatalogVenueResponse venue) {
        String currency = slot.getPricingTiers().isEmpty()
            ? "INR"
            : slot.getPricingTiers().get(0).getPrice().currency();
        return EbEventCreateRequest.of(
            slot.getTitle(),
            slot.getDescription(),
            venue.eventbriteVenueId(),
            slot.getStartTime(),
            slot.getEndTime(),
            slot.getCapacity(),
            slot.isRecurring(),
            currency
        );
    }

    private EbEventUpdateRequest toEbUpdateRequest(ShowSlot slot) {
        return EbEventUpdateRequest.of(
            slot.getTitle(),
            slot.getDescription(),
            slot.getStartTime(),
            slot.getEndTime()
        );
    }

    private void validateCreateRequest(CatalogVenueResponse venue, CreateShowSlotRequest request) {
        if (venue.eventbriteVenueId() == null || venue.eventbriteVenueId().isBlank()) {
            throw new BusinessRuleException("Cannot create slot for venue not yet synced to Eventbrite", "VENUE_NOT_SYNCED");
        }
        if (request.seatingMode() != null && request.seatingMode().name().equals("RESERVED")
            && (request.sourceSeatMapId() == null || request.sourceSeatMapId().isBlank())) {
            throw new ValidationException("sourceSeatMapId is required for RESERVED seating", "SLOT_VALIDATION_ERROR");
        }
        validatePricingTiers(request.pricingTiers());
    }

    private void validatePricingTiers(List<com.eventplatform.scheduling.api.dto.request.PricingTierRequest> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new BusinessRuleException("Slot must have at least one pricing tier before submission", "MISSING_PRICING_TIERS");
        }
        for (var tier : tiers) {
            if (tier.quota() == null || tier.quota() <= 0) {
                throw new ValidationException("quota must be greater than 0", "SLOT_VALIDATION_ERROR");
            }
            if (tier.priceAmount() == null || tier.priceAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new ValidationException("priceAmount must be non-negative", "SLOT_VALIDATION_ERROR");
            }
            if (tier.tierType() != null && tier.tierType().name().equals("FREE")
                && tier.priceAmount().compareTo(BigDecimal.ZERO) > 0) {
                throw new ValidationException("FREE tier must have priceAmount = 0", "SLOT_VALIDATION_ERROR");
            }
        }
    }
}
