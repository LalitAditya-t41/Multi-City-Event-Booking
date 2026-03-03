package com.eventplatform.discoverycatalog.service;

import com.eventplatform.discoverycatalog.api.dto.response.CityListResponse;
import com.eventplatform.discoverycatalog.api.dto.response.CityResponse;
import com.eventplatform.discoverycatalog.mapper.CityMapper;
import com.eventplatform.discoverycatalog.repository.CityRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CityCatalogService {

    private final CityRepository cityRepository;
    private final CityMapper cityMapper;

    public CityCatalogService(CityRepository cityRepository, CityMapper cityMapper) {
        this.cityRepository = cityRepository;
        this.cityMapper = cityMapper;
    }

    public CityListResponse listCities(Long organizationId) {
        List<CityResponse> cities = cityRepository.findByOrganizationId(organizationId).stream()
            .map(cityMapper::toResponse)
            .toList();
        return new CityListResponse(cities, cities.size());
    }
}
