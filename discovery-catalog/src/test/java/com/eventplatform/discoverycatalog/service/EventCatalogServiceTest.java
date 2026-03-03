package com.eventplatform.discoverycatalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.eventplatform.discoverycatalog.api.dto.request.EventCatalogSearchRequest;
import com.eventplatform.discoverycatalog.api.dto.response.EventCatalogItemResponse;
import com.eventplatform.discoverycatalog.api.dto.response.EventCatalogSearchResponse;
import com.eventplatform.discoverycatalog.domain.EventCatalogItem;
import com.eventplatform.discoverycatalog.domain.WebhookConfig;
import com.eventplatform.discoverycatalog.domain.enums.CatalogSource;
import com.eventplatform.discoverycatalog.domain.enums.EventSource;
import com.eventplatform.discoverycatalog.domain.enums.EventState;
import com.eventplatform.discoverycatalog.mapper.EventCatalogMapper;
import com.eventplatform.discoverycatalog.repository.CityRepository;
import com.eventplatform.discoverycatalog.repository.EventCatalogRepository;
import com.eventplatform.discoverycatalog.repository.VenueRepository;
import com.eventplatform.discoverycatalog.repository.WebhookConfigRepository;
import com.eventplatform.discoverycatalog.service.cache.EventCatalogSnapshotCache;
import com.eventplatform.discoverycatalog.service.metrics.EventCatalogMetrics;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class EventCatalogServiceTest {

    @Mock
    private EventCatalogRepository eventCatalogRepository;

    @Mock
    private CityRepository cityRepository;

    @Mock
    private VenueRepository venueRepository;

    @Mock
    private WebhookConfigRepository webhookConfigRepository;

    @Mock
    private EventCatalogSnapshotCache snapshotCache;

    @Mock
    private EventCatalogMapper eventCatalogMapper;

    @Mock
    private EventCatalogRefreshService refreshService;

    @Mock
    private EventCatalogMetrics metrics;

    @InjectMocks
    private EventCatalogService service;

    @Test
    void should_return_db_results_when_cache_miss() {
        Long orgId = 1L;
        Long cityId = 10L;
        EventCatalogSearchRequest request = new EventCatalogSearchRequest(cityId, null, null, null, null, null, 0, 20);

        when(cityRepository.existsById(cityId)).thenReturn(true);
        when(snapshotCache.getSnapshot(orgId, cityId)).thenReturn(Optional.empty());
        when(webhookConfigRepository.findByOrganizationId(orgId))
            .thenReturn(Optional.of(new WebhookConfig(orgId, "wh_1", "http://x", Instant.now())));

        EventCatalogItem item = new EventCatalogItem(orgId, cityId, "eb_1", "Show", Instant.now(), Instant.now().plusSeconds(3600));
        EventCatalogItemResponse response = new EventCatalogItemResponse(1L, cityId, null, "eb_1", "Show", null, null,
            item.getStartTime(), item.getEndTime(), EventState.PUBLISHED, EventSource.EVENTBRITE_EXTERNAL, null);

        when(eventCatalogRepository.findAll(org.mockito.ArgumentMatchers.<Specification<EventCatalogItem>>any(),
            org.mockito.ArgumentMatchers.eq(PageRequest.of(0, 20))))
            .thenReturn(new PageImpl<>(java.util.List.of(item)));
        when(eventCatalogRepository.findByOrganizationIdAndCityIdAndDeletedAtIsNull(orgId, cityId))
            .thenReturn(java.util.List.of(item));
        when(eventCatalogMapper.toResponse(item)).thenReturn(response);

        EventCatalogSearchResponse result = service.search(orgId, request);

        assertThat(result.events()).hasSize(1);
        assertThat(result.source()).isEqualTo(CatalogSource.DB);
    }
}
