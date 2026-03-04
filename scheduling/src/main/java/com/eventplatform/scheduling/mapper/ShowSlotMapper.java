package com.eventplatform.scheduling.mapper;

import com.eventplatform.scheduling.api.dto.request.CreateShowSlotRequest;
import com.eventplatform.scheduling.api.dto.request.PricingTierRequest;
import com.eventplatform.scheduling.api.dto.response.ConflictAlternativeDto;
import com.eventplatform.scheduling.api.dto.response.ShowSlotOccurrenceResponse;
import com.eventplatform.scheduling.api.dto.response.ShowSlotPricingTierResponse;
import com.eventplatform.scheduling.api.dto.response.ShowSlotResponse;
import com.eventplatform.scheduling.api.dto.response.TimeWindowOptionDto;
import com.eventplatform.scheduling.api.dto.response.VenueOptionDto;
import com.eventplatform.scheduling.domain.ShowSlot;
import com.eventplatform.scheduling.domain.ShowSlotOccurrence;
import com.eventplatform.scheduling.domain.ShowSlotPricingTier;
import com.eventplatform.scheduling.domain.enums.TierType;
import com.eventplatform.scheduling.domain.value.ConflictAlternativeResponse;
import com.eventplatform.scheduling.domain.value.TimeWindowOption;
import com.eventplatform.scheduling.domain.value.VenueOption;
import com.eventplatform.shared.common.domain.Money;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ShowSlotMapper {

    @Mapping(target = "pricingTiers", expression = "java(mapPricingTiers(slot.getPricingTiers()))")
    ShowSlotResponse toResponse(ShowSlot slot);

    ShowSlotOccurrenceResponse toOccurrenceResponse(ShowSlotOccurrence occurrence);

    @Mapping(target = "priceAmount", source = "price.amount")
    @Mapping(target = "currency", source = "price.currency")
    ShowSlotPricingTierResponse toPricingTierResponse(ShowSlotPricingTier tier);

    default List<ShowSlotPricingTierResponse> mapPricingTiers(List<ShowSlotPricingTier> tiers) {
        return tiers == null ? List.of() : tiers.stream().map(this::toPricingTierResponse).toList();
    }

    default ShowSlot toEntity(Long organizationId, Long venueId, Long cityId, CreateShowSlotRequest request) {
        return new ShowSlot(
            organizationId,
            venueId,
            cityId,
            request.title(),
            request.description(),
            request.startTime(),
            request.endTime(),
            request.seatingMode(),
            request.capacity(),
            Boolean.TRUE.equals(request.isRecurring()),
            request.recurrenceRule(),
            request.sourceSeatMapId()
        );
    }

    default List<ShowSlotPricingTier> toPricingTiers(List<PricingTierRequest> tiers) {
        if (tiers == null) {
            return List.of();
        }
        return tiers.stream().map(this::toPricingTier).toList();
    }

    default ShowSlotPricingTier toPricingTier(PricingTierRequest request) {
        Money price = new Money(request.priceAmount(), request.currency());
        TierType type = request.tierType();
        return new ShowSlotPricingTier(request.name(), price, request.quota(), type);
    }

    default ConflictAlternativeDto toConflictDto(ConflictAlternativeResponse response) {
        return new ConflictAlternativeDto(
            mapTimeWindows(response.sameVenueAlternatives()),
            mapVenues(response.nearbyVenueAlternatives()),
            mapTimeWindows(response.adjustedTimeOptions())
        );
    }

    default List<TimeWindowOptionDto> mapTimeWindows(List<TimeWindowOption> options) {
        if (options == null) {
            return List.of();
        }
        return options.stream().map(this::toTimeWindowDto).toList();
    }

    default TimeWindowOptionDto toTimeWindowDto(TimeWindowOption option) {
        return new TimeWindowOptionDto(option.proposedStart(), option.proposedEnd());
    }

    default List<VenueOptionDto> mapVenues(List<VenueOption> options) {
        if (options == null) {
            return List.of();
        }
        return options.stream().map(this::toVenueDto).toList();
    }

    default VenueOptionDto toVenueDto(VenueOption option) {
        return new VenueOptionDto(option.venueId(), option.venueName(), option.city(), option.capacity());
    }
}
