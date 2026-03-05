package com.eventplatform.discoverycatalog.event.published;

public record VenueDriftDetectedEvent(Long venueId, String ebVenueId, String driftDescription) {}
