package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.response.EbScheduleResponse;

public interface EbScheduleService {
  EbScheduleResponse createSchedule(Long organizationId, String eventId, String recurrenceRule);
}
