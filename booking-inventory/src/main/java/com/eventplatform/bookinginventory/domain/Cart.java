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
        @AttributeOverride(name = "amount", column = @Column(name = "discount_amount", nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false))
    })
    private Money discountAmount;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {
    }

    public Cart(Long userId, Long showSlotId, Long orgId, SeatingMode seatingMode, Duration ttl, String currency) {
        this.userId = userId;
        this.showSlotId = showSlotId;
        this.orgId = orgId;
        this.seatingMode = seatingMode;
        this.status = CartStatus.PENDING;
        this.expiresAt = Instant.now().plus(ttl);
        this.discountAmount = new Money(BigDecimal.ZERO, currency);
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

    public void setDiscountAmount(Money discountAmount) {
        this.discountAmount = discountAmount;
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

    public Money getDiscountAmount() {
        return discountAmount;
    }

    public List<CartItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
