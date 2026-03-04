package com.eventplatform.promotions.domain;

import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "coupon_redemptions")
public class CouponRedemption extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id", nullable = false)
    private Coupon coupon;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "cart_id", nullable = false)
    private Long cartId;

    @Column(name = "discount_amount", nullable = false)
    private BigDecimal discountAmount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "redeemed_at", nullable = false)
    private Instant redeemedAt;

    @Column(name = "voided", nullable = false)
    private boolean voided;

    @Column(name = "voided_at")
    private Instant voidedAt;

    protected CouponRedemption() {
    }

    public CouponRedemption(Coupon coupon, Long userId, Long bookingId, Long cartId, BigDecimal discountAmount, String currency) {
        this.coupon = coupon;
        this.userId = userId;
        this.bookingId = bookingId;
        this.cartId = cartId;
        this.discountAmount = discountAmount;
        this.currency = currency;
        this.redeemedAt = Instant.now();
        this.voided = false;
    }

    public void voidRedemption() {
        this.voided = true;
        this.voidedAt = Instant.now();
    }

    public Coupon getCoupon() { return coupon; }
    public Long getUserId() { return userId; }
    public Long getBookingId() { return bookingId; }
    public Long getCartId() { return cartId; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public String getCurrency() { return currency; }
    public boolean isVoided() { return voided; }
}
