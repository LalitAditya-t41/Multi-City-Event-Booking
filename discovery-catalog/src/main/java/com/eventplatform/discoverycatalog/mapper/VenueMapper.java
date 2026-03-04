package com.eventplatform.discoverycatalog.mapper;

import com.eventplatform.discoverycatalog.api.dto.request.CreateVenueRequest;
import com.eventplatform.discoverycatalog.api.dto.response.VenueResponse;
import com.eventplatform.discoverycatalog.api.dto.response.VenueSeatResponse;
import com.eventplatform.discoverycatalog.domain.Venue;
import com.eventplatform.discoverycatalog.domain.VenueSeat;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface VenueMapper {
    @Mapping(target = "cityId", source = "cityId")
    VenueResponse toResponse(Venue venue);

    VenueSeatResponse toSeatResponse(VenueSeat seat);

    default Venue toEntity(Long organizationId, CreateVenueRequest request) {
        String address = formatAddress(request.addressLine1(), request.addressLine2(), request.country());
        return new Venue(
            organizationId,
            request.cityId(),
            request.name(),
            address,
            request.zipCode(),
            request.latitude(),
            request.longitude(),
            request.capacity(),
            request.seatingMode()
        );
    }

    default String formatAddress(String addressLine1, String addressLine2, String country) {
        StringBuilder builder = new StringBuilder();
        if (addressLine1 != null && !addressLine1.isBlank()) {
            builder.append(addressLine1.trim());
        }
        if (addressLine2 != null && !addressLine2.isBlank()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(addressLine2.trim());
        }
        if (country != null && !country.isBlank()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(country.trim());
        }
        return builder.length() == 0 ? null : builder.toString();
    }
}
