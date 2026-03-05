package com.eventplatform.discoverycatalog.domain;

import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "venue_seat",
    uniqueConstraints =
        @UniqueConstraint(columnNames = {"venue_id", "section", "row_label", "seat_number"}))
public class VenueSeat extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "venue_id", nullable = false)
  private Venue venue;

  @Column(name = "section", nullable = false)
  private String section;

  @Column(name = "row_label", nullable = false)
  private String rowLabel;

  @Column(name = "seat_number", nullable = false)
  private String seatNumber;

  @Column(name = "tier_name", nullable = false)
  private String tierName;

  @Column(name = "is_accessible", nullable = false)
  private boolean accessible;

  protected VenueSeat() {}

  public VenueSeat(
      Venue venue,
      String section,
      String rowLabel,
      String seatNumber,
      String tierName,
      boolean accessible) {
    this.venue = venue;
    this.section = section;
    this.rowLabel = rowLabel;
    this.seatNumber = seatNumber;
    this.tierName = tierName;
    this.accessible = accessible;
  }

  public Venue getVenue() {
    return venue;
  }

  public String getSection() {
    return section;
  }

  public String getRowLabel() {
    return rowLabel;
  }

  public String getSeatNumber() {
    return seatNumber;
  }

  public String getTierName() {
    return tierName;
  }

  public boolean isAccessible() {
    return accessible;
  }
}
