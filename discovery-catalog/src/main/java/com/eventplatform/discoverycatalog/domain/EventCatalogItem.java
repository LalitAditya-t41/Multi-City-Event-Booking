package com.eventplatform.discoverycatalog.domain;

import com.eventplatform.discoverycatalog.domain.enums.EventSource;
import com.eventplatform.discoverycatalog.domain.enums.EventState;
import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "event_catalog")
public class EventCatalogItem extends BaseEntity {

  @Column(name = "organization_id", nullable = false)
  private Long organizationId;

  @Column(name = "city_id", nullable = false)
  private Long cityId;

  @Column(name = "venue_id")
  private Long venueId;

  @Column(name = "eventbrite_event_id", nullable = false, unique = true)
  private String eventbriteEventId;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "description")
  private String description;

  @Column(name = "url")
  private String url;

  @Column(name = "start_time", nullable = false)
  private Instant startTime;

  @Column(name = "end_time", nullable = false)
  private Instant endTime;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false)
  private EventState state;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false)
  private EventSource source;

  @Column(name = "currency")
  private String currency;

  @Column(name = "eventbrite_changed_at")
  private Instant eventbriteChangedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected EventCatalogItem() {}

  public EventCatalogItem(
      Long organizationId,
      Long cityId,
      String eventbriteEventId,
      String name,
      Instant startTime,
      Instant endTime) {
    this.organizationId = organizationId;
    this.cityId = cityId;
    this.eventbriteEventId = eventbriteEventId;
    this.name = name;
    this.startTime = startTime;
    this.endTime = endTime;
    this.state = EventState.PUBLISHED;
    this.source = EventSource.EVENTBRITE_EXTERNAL;
  }

  public void applyEventbriteDetails(
      Long venueId,
      String description,
      String url,
      EventState state,
      EventSource source,
      String currency,
      Instant changedAt) {
    this.venueId = venueId;
    this.description = description;
    this.url = url;
    this.state = state;
    this.source = source;
    this.currency = currency;
    this.eventbriteChangedAt = changedAt;
  }

  public void updateFrom(EventCatalogItem updated) {
    this.name = updated.name;
    this.description = updated.description;
    this.url = updated.url;
    this.startTime = updated.startTime;
    this.endTime = updated.endTime;
    this.state = updated.state;
    this.source = updated.source;
    this.currency = updated.currency;
    this.eventbriteChangedAt = updated.eventbriteChangedAt;
    this.venueId = updated.venueId;
  }

  public void softDelete(Instant deletedAt) {
    this.deletedAt = deletedAt;
  }

  public void markCancelled(Instant changedAt) {
    this.state = EventState.CANCELLED;
    this.eventbriteChangedAt = changedAt;
  }

  public Long getOrganizationId() {
    return organizationId;
  }

  public Long getCityId() {
    return cityId;
  }

  public Long getVenueId() {
    return venueId;
  }

  public String getEventbriteEventId() {
    return eventbriteEventId;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getUrl() {
    return url;
  }

  public Instant getStartTime() {
    return startTime;
  }

  public Instant getEndTime() {
    return endTime;
  }

  public EventState getState() {
    return state;
  }

  public EventSource getSource() {
    return source;
  }

  public String getCurrency() {
    return currency;
  }

  public Instant getEventbriteChangedAt() {
    return eventbriteChangedAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }
}
