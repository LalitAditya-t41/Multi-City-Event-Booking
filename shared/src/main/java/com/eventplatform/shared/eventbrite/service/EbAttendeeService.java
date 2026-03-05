package com.eventplatform.shared.eventbrite.service;

import com.eventplatform.shared.eventbrite.dto.response.EbAttendeeResponse;
import java.util.List;

public interface EbAttendeeService {

    List<EbAttendeeResponse> getAttendeesByEvent(String orgToken, String eventId);
}
