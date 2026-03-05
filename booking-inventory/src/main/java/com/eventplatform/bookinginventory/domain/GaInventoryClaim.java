package com.eventplatform.bookinginventory.domain;

import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ga_inventory_claims")
public class GaInventoryClaim extends BaseEntity {

  @Column(name = "show_slot_id", nullable = false)
  private Long showSlotId;

  @Column(name = "pricing_tier_id", nullable = false)
  private Long pricingTierId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "cart_id")
  private Long cartId;

  @Column(name = "quantity", nullable = false)
  private Integer quantity;

  @Enumerated(EnumType.STRING)
  @Column(name = "lock_state", nullable = false)
  private SeatLockState lockState;

  @Column(name = "locked_until", nullable = false)
  private Instant lockedUntil;

  @Column(name = "booking_ref")
  private String bookingRef;

  protected GaInventoryClaim() {}

  public GaInventoryClaim(
      Long showSlotId,
      Long pricingTierId,
      Long userId,
      Long cartId,
      Integer quantity,
      Instant lockedUntil) {
    this.showSlotId = showSlotId;
    this.pricingTierId = pricingTierId;
    this.userId = userId;
    this.cartId = cartId;
    this.quantity = quantity;
    this.lockState = SeatLockState.SOFT_LOCKED;
    this.lockedUntil = lockedUntil;
  }

  public void hardLock(Instant lockUntil) {
    this.lockState = SeatLockState.HARD_LOCKED;
    this.lockedUntil = lockUntil;
  }

  public void confirm(String bookingRef) {
    this.lockState = SeatLockState.CONFIRMED;
    this.bookingRef = bookingRef;
    this.lockedUntil = null;
  }

  public void release() {
    this.lockState = SeatLockState.AVAILABLE;
    this.lockedUntil = null;
  }

  public Long getShowSlotId() {
    return showSlotId;
  }

  public Long getPricingTierId() {
    return pricingTierId;
  }

  public Long getUserId() {
    return userId;
  }

  public Long getCartId() {
    return cartId;
  }

  public Integer getQuantity() {
    return quantity;
  }

  public SeatLockState getLockState() {
    return lockState;
  }

  public Instant getLockedUntil() {
    return lockedUntil;
  }
}
