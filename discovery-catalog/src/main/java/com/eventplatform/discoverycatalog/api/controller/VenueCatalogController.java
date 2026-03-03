package com.eventplatform.discoverycatalog.api.controller;

import com.eventplatform.discoverycatalog.api.dto.response.VenueListResponse;
import com.eventplatform.discoverycatalog.service.VenueCatalogService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalog")
public class VenueCatalogController {

    private final VenueCatalogService venueCatalogService;
    private final Long defaultOrgId;

    public VenueCatalogController(VenueCatalogService venueCatalogService, @Value("${app.default-org-id}") Long defaultOrgId) {
        this.venueCatalogService = venueCatalogService;
        this.defaultOrgId = defaultOrgId;
    }

    @GetMapping("/venues")
    public VenueListResponse listVenues(
        @RequestParam(name = "cityId") Long cityId,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        return venueCatalogService.listVenues(defaultOrgId, cityId, page, size);
    }
}
