package com.eventplatform.discoverycatalog.repository;

import com.eventplatform.discoverycatalog.domain.City;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CityRepository extends JpaRepository<City, Long> {
  List<City> findByOrganizationId(Long organizationId);
}
