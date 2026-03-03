package com.eventplatform.discoverycatalog.api.controller;

import com.eventplatform.discoverycatalog.api.dto.request.EventCatalogSearchRequest;
import com.eventplatform.discoverycatalog.api.dto.response.EventCatalogSearchResponse;
import com.eventplatform.discoverycatalog.domain.enums.EventState;
import com.eventplatform.discoverycatalog.service.EventCatalogService;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalog")
public class EventCatalogController {

    private final EventCatalogService eventCatalogService;
    private final Long defaultOrgId;

    public EventCatalogController(EventCatalogService eventCatalogService, @Value("${app.default-org-id}") Long defaultOrgId) {
        this.eventCatalogService = eventCatalogService;
        this.defaultOrgId = defaultOrgId;
    }

    @GetMapping("/events")
    public EventCatalogSearchResponse searchEvents(
        @RequestParam(name = "cityId") Long cityId,
        @RequestParam(name = "venueId", required = false) Long venueId,
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "state", required = false) EventState state,
        @RequestParam(name = "startAfter", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startAfter,
        @RequestParam(name = "startBefore", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startBefore,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        EventCatalogSearchRequest request = new EventCatalogSearchRequest(
            cityId,
            venueId,
            q,
            state,
            startAfter,
            startBefore,
            page,
            size
        );
        return eventCatalogService.search(defaultOrgId, request);
    }
}
