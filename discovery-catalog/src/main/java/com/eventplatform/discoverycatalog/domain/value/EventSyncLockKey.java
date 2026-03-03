package com.eventplatform.discoverycatalog.domain.value;

public record EventSyncLockKey(Long organizationId, Long cityId, Long venueId) {
    public String asString() {
        String venuePart = venueId == null ? "org" : venueId.toString();
        return "event-sync-lock:%d:%d:%s".formatted(organizationId, cityId, venuePart);
    }
}
