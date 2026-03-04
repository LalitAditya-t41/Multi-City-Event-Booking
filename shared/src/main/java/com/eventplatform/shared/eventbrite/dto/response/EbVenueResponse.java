package com.eventplatform.shared.eventbrite.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Eventbrite v3 venue response.
 * The mock / real EB API returns a nested {@code address} object;
 * the flat accessors below extract the relevant fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EbVenueResponse(
    String id,
    String name,
    @JsonProperty("address") AddressField address,
    String latitude,
    String longitude
) {
    /** Returns the city string from the nested address object, or null. */
    public String city() {
        return address != null ? address.city() : null;
    }

    /** Returns the country string from the nested address object, or null. */
    public String country() {
        return address != null ? address.country() : null;
    }

    /** Returns the zip/postal-code from the nested address object, or null. */
    public String zipCode() {
        return address != null ? address.postalCode() : null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AddressField(
        @JsonProperty("address_1")   String addressLine1,
        @JsonProperty("address_2")   String addressLine2,
        @JsonProperty("city")        String city,
        @JsonProperty("country")     String country,
        @JsonProperty("postal_code") String postalCode,
        @JsonProperty("latitude")    String latitude,
        @JsonProperty("longitude")   String longitude
    ) {}
}
