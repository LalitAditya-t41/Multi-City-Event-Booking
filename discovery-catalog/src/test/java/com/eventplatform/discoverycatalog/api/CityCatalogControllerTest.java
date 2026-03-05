package com.eventplatform.discoverycatalog.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.discoverycatalog.api.dto.response.CityListResponse;
import com.eventplatform.discoverycatalog.api.dto.response.CityResponse;
import com.eventplatform.discoverycatalog.mapper.VenueMapper;
import com.eventplatform.discoverycatalog.service.CityCatalogService;
import com.eventplatform.discoverycatalog.service.EventCatalogService;
import com.eventplatform.discoverycatalog.service.VenueCatalogService;
import com.eventplatform.discoverycatalog.service.VenueService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = com.eventplatform.discoverycatalog.DiscoveryCatalogTestApplication.class,
    properties = {
      "app.default-org-id=1",
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
    })
@AutoConfigureMockMvc(addFilters = false)
class CityCatalogControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CityCatalogService cityCatalogService;

  @MockitoBean private EventCatalogService eventCatalogService;

  @MockitoBean private VenueCatalogService venueCatalogService;

  @MockitoBean private VenueService venueService;

  @MockitoBean private VenueMapper venueMapper;

  @Test
  void should_return_200_when_cities_listed() throws Exception {
    when(cityCatalogService.listCities(1L))
        .thenReturn(
            new CityListResponse(
                List.of(new CityResponse(1L, "NYC", null, null, null, null, null)), 1));

    mockMvc.perform(get("/api/v1/catalog/cities")).andExpect(status().isOk());
  }
}
