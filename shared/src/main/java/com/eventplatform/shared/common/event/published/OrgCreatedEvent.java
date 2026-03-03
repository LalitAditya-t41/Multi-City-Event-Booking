package com.eventplatform.shared.common.event.published;

import java.time.Instant;

public record OrgCreatedEvent(Long organizationId, Long adminUserId, Instant createdAt) {
}
