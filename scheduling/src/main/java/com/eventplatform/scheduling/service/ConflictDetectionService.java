package com.eventplatform.scheduling.service;

import com.eventplatform.scheduling.domain.ShowSlot;
import com.eventplatform.scheduling.domain.value.ConflictAlternativeResponse;
import com.eventplatform.scheduling.domain.value.TimeWindowOption;
import com.eventplatform.scheduling.domain.value.VenueOption;
import com.eventplatform.scheduling.exception.SlotConflictException;
import com.eventplatform.scheduling.repository.ShowSlotRepository;
import com.eventplatform.scheduling.service.client.CatalogVenueResponse;
import com.eventplatform.scheduling.service.client.VenueCatalogClient;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ConflictDetectionService {

    private final ShowSlotRepository showSlotRepository;
    private final VenueCatalogClient venueCatalogClient;
    private final Duration gapPolicy;

    public ConflictDetectionService(
        ShowSlotRepository showSlotRepository,
        VenueCatalogClient venueCatalogClient,
        @Value("${scheduling.turnaround-gap-minutes:60}") long gapMinutes
    ) {
        this.showSlotRepository = showSlotRepository;
        this.venueCatalogClient = venueCatalogClient;
        this.gapPolicy = Duration.ofMinutes(gapMinutes);
    }

    public void validateOrThrow(Long organizationId, CatalogVenueResponse venue, ZonedDateTime startTime, ZonedDateTime endTime) {
        List<ShowSlot> overlaps = showSlotRepository.findConflicts(venue.id(), startTime, endTime);
        boolean gapViolation = hasGapViolation(venue.id(), startTime, endTime);

        if (!overlaps.isEmpty() || gapViolation) {
            ConflictAlternativeResponse alternatives = buildAlternatives(organizationId, venue, startTime, endTime);
            throw new SlotConflictException("Venue has an overlapping slot or turnaround gap violation", alternatives);
        }
    }

    private boolean hasGapViolation(Long venueId, ZonedDateTime startTime, ZonedDateTime endTime) {
        return showSlotRepository.findFirstByVenueIdAndEndTimeLessThanEqualOrderByEndTimeDesc(venueId, startTime)
            .map(slot -> Duration.between(slot.getEndTime(), startTime).compareTo(gapPolicy) < 0)
            .orElse(false)
            || showSlotRepository.findFirstByVenueIdAndStartTimeGreaterThanEqualOrderByStartTimeAsc(venueId, endTime)
                .map(slot -> Duration.between(endTime, slot.getStartTime()).compareTo(gapPolicy) < 0)
                .orElse(false);
    }

    private ConflictAlternativeResponse buildAlternatives(
        Long organizationId,
        CatalogVenueResponse venue,
        ZonedDateTime startTime,
        ZonedDateTime endTime
    ) {
        Duration duration = Duration.between(startTime, endTime);

        TimeWindowOption sameVenue = showSlotRepository
            .findFirstByVenueIdAndEndTimeLessThanEqualOrderByEndTimeDesc(venue.id(), startTime)
            .map(prev -> new TimeWindowOption(prev.getEndTime().plus(gapPolicy), prev.getEndTime().plus(gapPolicy).plus(duration)))
            .orElse(new TimeWindowOption(startTime.plusDays(1), endTime.plusDays(1)));

        TimeWindowOption adjusted = showSlotRepository
            .findFirstByVenueIdAndStartTimeGreaterThanEqualOrderByStartTimeAsc(venue.id(), endTime)
            .map(next -> {
                ZonedDateTime newEnd = next.getStartTime().minus(gapPolicy);
                ZonedDateTime newStart = newEnd.minus(duration);
                return new TimeWindowOption(newStart, newEnd);
            })
            .orElse(sameVenue);

        int venueCapacity = venue.capacity() == null ? 0 : venue.capacity();
        List<VenueOption> nearby = venueCatalogClient.listVenuesByCity(venue.cityId(), organizationId).stream()
            .filter(candidate -> !candidate.id().equals(venue.id()))
            .sorted(Comparator.comparingInt(candidate -> {
                int candidateCapacity = candidate.capacity() == null ? 0 : candidate.capacity();
                return Math.abs(candidateCapacity - venueCapacity);
            }))
            .limit(3)
            .map(candidate -> new VenueOption(candidate.id(), candidate.name(), String.valueOf(venue.cityId()),
                candidate.capacity() == null ? 0 : candidate.capacity()))
            .toList();

        return new ConflictAlternativeResponse(
            List.of(sameVenue),
            nearby,
            List.of(adjusted)
        );
    }
}
