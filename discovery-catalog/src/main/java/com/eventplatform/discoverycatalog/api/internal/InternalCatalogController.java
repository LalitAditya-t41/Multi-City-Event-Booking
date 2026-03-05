package com.eventplatform.discoverycatalog.api.internal;

import com.eventplatform.discoverycatalog.service.internal.InternalCatalogQueryService;
import com.eventplatform.discoverycatalog.service.internal.InternalCatalogQueryService.EventEbMetadataResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/catalog/events")
public class InternalCatalogController {

    private final InternalCatalogQueryService internalCatalogQueryService;

    public InternalCatalogController(InternalCatalogQueryService internalCatalogQueryService) {
        this.internalCatalogQueryService = internalCatalogQueryService;
    }

    @GetMapping("/{eventId}/eb-metadata")
    public EventEbMetadataResponse eventMetadata(@PathVariable Long eventId) {
        return internalCatalogQueryService.getEventEbMetadata(eventId);
    }
}
