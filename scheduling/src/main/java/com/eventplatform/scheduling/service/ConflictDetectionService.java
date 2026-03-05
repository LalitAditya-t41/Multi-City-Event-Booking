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
      @Value("${scheduling.turnaround-gap-minutes:60}") long gapMinutes) {
    this.showSlotRepository = showSlotRepository;
    this.venueCatalogClient = venueCatalogClient;
    this.gapPolicy = Duration.ofMinutes(gapMinutes);
  }

  public void validateOrThrow(
      Long organizationId,
      CatalogVenueResponse venue,
      ZonedDateTime startTime,
      ZonedDateTime endTime) {
    validateOrThrow(organizationId, venue, startTime, endTime, null);
  }

  public void validateOrThrow(
      Long organizationId,
      CatalogVenueResponse venue,
      ZonedDateTime startTime,
      ZonedDateTime endTime,
      Long excludeSlotId) {
    List<ShowSlot> overlaps =
        showSlotRepository.findConflictsExcludingId(venue.id(), startTime, endTime, excludeSlotId);
    boolean gapViolation = hasGapViolation(venue.id(), startTime, endTime, excludeSlotId);

    if (!overlaps.isEmpty() || gapViolation) {
      ConflictAlternativeResponse alternatives =
          buildAlternatives(organizationId, venue, startTime, endTime, excludeSlotId);
      throw new SlotConflictException(
          "Venue has an overlapping slot or turnaround gap violation", alternatives);
    }
  }

  private boolean hasGapViolation(
      Long venueId, ZonedDateTime startTime, ZonedDateTime endTime, Long excludeSlotId) {
    return showSlotRepository
            .findPrevSlotForGap(venueId, startTime, excludeSlotId)
            .map(slot -> Duration.between(slot.getEndTime(), startTime).compareTo(gapPolicy) < 0)
            .orElse(false)
        || showSlotRepository
            .findNextSlotForGap(venueId, endTime, excludeSlotId)
            .map(slot -> Duration.between(endTime, slot.getStartTime()).compareTo(gapPolicy) < 0)
            .orElse(false);
  }

  private ConflictAlternativeResponse buildAlternatives(
      Long organizationId,
      CatalogVenueResponse venue,
      ZonedDateTime startTime,
      ZonedDateTime endTime,
      Long excludeSlotId) {
    Duration duration = Duration.between(startTime, endTime);

    List<TimeWindowOption> sameVenueAlternatives =
        buildSameVenueAlternatives(venue.id(), startTime, endTime, duration, excludeSlotId);

    TimeWindowOption adjusted =
        showSlotRepository
            .findPrevSlotForGap(venue.id(), startTime, excludeSlotId)
            .map(
                prev -> {
                  ZonedDateTime newStart = prev.getEndTime().plus(gapPolicy).plusMinutes(1);
                  return new TimeWindowOption(newStart, newStart.plus(duration));
                })
            .orElseGet(
                () ->
                    sameVenueAlternatives.isEmpty()
                        ? new TimeWindowOption(startTime.plusDays(1), endTime.plusDays(1))
                        : sameVenueAlternatives.getFirst());

    int venueCapacity = venue.capacity() == null ? 0 : venue.capacity();
    List<VenueOption> nearby =
        venueCatalogClient.listVenuesByCity(venue.cityId(), organizationId).stream()
            .filter(candidate -> !candidate.id().equals(venue.id()))
            .filter(
                candidate ->
                    candidate.eventbriteVenueId() != null
                        && !candidate.eventbriteVenueId().isBlank())
            .sorted(
                Comparator.comparingInt(
                    candidate -> {
                      int candidateCapacity =
                          candidate.capacity() == null ? 0 : candidate.capacity();
                      return Math.abs(candidateCapacity - venueCapacity);
                    }))
            .limit(3)
            .map(
                candidate ->
                    new VenueOption(
                        candidate.id(),
                        candidate.name(),
                        String.valueOf(venue.cityId()),
                        candidate.capacity() == null ? 0 : candidate.capacity()))
            .toList();

    return new ConflictAlternativeResponse(sameVenueAlternatives, nearby, List.of(adjusted));
  }

  private List<TimeWindowOption> buildSameVenueAlternatives(
      Long venueId,
      ZonedDateTime startTime,
      ZonedDateTime endTime,
      Duration duration,
      Long excludeSlotId) {
    List<TimeWindowOption> options = new java.util.ArrayList<>();
    for (int i = 0; i < 3; i++) {
      ZonedDateTime candidateStart = endTime.plus(gapPolicy).plus(duration.multipliedBy(i));
      ZonedDateTime candidateEnd = candidateStart.plus(duration);
      boolean conflict =
          !showSlotRepository
              .findConflictsExcludingId(venueId, candidateStart, candidateEnd, excludeSlotId)
              .isEmpty();
      boolean gapViolation = hasGapViolation(venueId, candidateStart, candidateEnd, excludeSlotId);
      if (!conflict && !gapViolation) {
        options.add(new TimeWindowOption(candidateStart, candidateEnd));
      }
    }
    return options;
  }
}
