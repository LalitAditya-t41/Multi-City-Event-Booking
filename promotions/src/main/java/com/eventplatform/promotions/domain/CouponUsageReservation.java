package com.eventplatform.promotions.domain;

import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "coupon_usage_reservations")
public class CouponUsageReservation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id", nullable = false)
    private Coupon coupon;

    @Column(name = "cart_id", nullable = false)
    private Long cartId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "reserved_at", nullable = false)
    private Instant reservedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "released", nullable = false)
    private boolean released;

    protected CouponUsageReservation() {
    }

    public CouponUsageReservation(Coupon coupon, Long cartId, Long userId, Instant expiresAt) {
        this.coupon = coupon;
        this.cartId = cartId;
        this.userId = userId;
        this.expiresAt = expiresAt;
        this.reservedAt = Instant.now();
        this.released = false;
    }

    public void release() {
        this.released = true;
    }

    public boolean isActive(Instant now) {
        return !released && expiresAt.isAfter(now);
    }

    public Coupon getCoupon() { return coupon; }
    public Long getCartId() { return cartId; }
    public Long getUserId() { return userId; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isReleased() { return released; }
}
