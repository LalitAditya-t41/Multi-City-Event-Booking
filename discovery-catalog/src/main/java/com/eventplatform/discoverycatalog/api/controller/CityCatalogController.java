package com.eventplatform.discoverycatalog.api.controller;

import com.eventplatform.discoverycatalog.api.dto.response.CityListResponse;
import com.eventplatform.discoverycatalog.service.CityCatalogService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalog")
public class CityCatalogController {

  private final CityCatalogService cityCatalogService;
  private final Long defaultOrgId;

  public CityCatalogController(
      CityCatalogService cityCatalogService, @Value("${app.default-org-id}") Long defaultOrgId) {
    this.cityCatalogService = cityCatalogService;
    this.defaultOrgId = defaultOrgId;
  }

  @GetMapping("/cities")
  public CityListResponse listCities() {
    return cityCatalogService.listCities(defaultOrgId);
  }
}
