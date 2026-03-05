package com.eventplatform.bookinginventory.domain;

import com.eventplatform.bookinginventory.domain.enums.CartStatus;
import com.eventplatform.shared.common.domain.BaseEntity;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "carts")
public class Cart extends BaseEntity {

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "show_slot_id", nullable = false)
  private Long showSlotId;

  @Column(name = "org_id", nullable = false)
  private Long orgId;

  @Enumerated(EnumType.STRING)
  @Column(name = "seating_mode", nullable = false)
  private SeatingMode seatingMode;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private CartStatus status;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "coupon_code")
  private String couponCode;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(
        name = "amount",
        column = @Column(name = "group_discount_amount", nullable = false)),
    @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false))
  })
  private Money groupDiscountAmount;

  /**
   * Coupon discount applied by promotions module via CouponAppliedEvent. Stored as raw BigDecimal —
   * currency is inferred from groupDiscountAmount.currency(). Default 0.00 (no coupon applied).
   */
  @Column(name = "coupon_discount_amount", nullable = false)
  private java.math.BigDecimal couponDiscountAmount = java.math.BigDecimal.ZERO;

  @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<CartItem> items = new ArrayList<>();

  protected Cart() {}

  public Cart(
      Long userId,
      Long showSlotId,
      Long orgId,
      SeatingMode seatingMode,
      Duration ttl,
      String currency) {
    this.userId = userId;
    this.showSlotId = showSlotId;
    this.orgId = orgId;
    this.seatingMode = seatingMode;
    this.status = CartStatus.PENDING;
    this.expiresAt = Instant.now().plus(ttl);
    this.groupDiscountAmount = new Money(BigDecimal.ZERO, currency);
    this.couponDiscountAmount = BigDecimal.ZERO;
  }

  public void addItem(CartItem item) {
    item.attachTo(this);
    this.items.add(item);
  }

  public void removeItem(CartItem item) {
    this.items.remove(item);
    item.detach();
  }

  public void confirm() {
    this.status = CartStatus.CONFIRMED;
  }

  public void abandon() {
    this.status = CartStatus.ABANDONED;
  }

  public void expire() {
    this.status = CartStatus.EXPIRED;
  }

  public boolean isExpired(Instant now) {
    return !this.expiresAt.isAfter(now);
  }

  public void ensurePending() {
    if (status != CartStatus.PENDING) {
      throw new BusinessRuleException("Cart is not pending", "CART_NOT_PENDING");
    }
  }

  public void extendTtl(Duration ttl) {
    this.expiresAt = Instant.now().plus(ttl);
  }

  public void setGroupDiscountAmount(Money groupDiscountAmount) {
    this.groupDiscountAmount = groupDiscountAmount;
  }

  /** Returns the coupon discount as a Money using this cart's currency. */
  public Money getCouponDiscountAmount() {
    return new Money(couponDiscountAmount, groupDiscountAmount.currency());
  }

  /**
   * Called by booking-inventory's CouponAppliedListener when promotions module confirms a coupon
   * has been validated and applied to this cart.
   */
  public void setCouponDiscountAmount(java.math.BigDecimal amount) {
    this.couponDiscountAmount = amount;
  }

  public Long getUserId() {
    return userId;
  }

  public Long getShowSlotId() {
    return showSlotId;
  }

  public Long getOrgId() {
    return orgId;
  }

  public SeatingMode getSeatingMode() {
    return seatingMode;
  }

  public CartStatus getStatus() {
    return status;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public String getCouponCode() {
    return couponCode;
  }

  public void setCouponCode(String couponCode) {
    this.couponCode = couponCode;
  }

  public Money getGroupDiscountAmount() {
    return groupDiscountAmount;
  }

  public List<CartItem> getItems() {
    return Collections.unmodifiableList(items);
  }
}
