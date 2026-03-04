package com.eventplatform.bookinginventory.domain;

import com.eventplatform.shared.common.domain.BaseEntity;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "cart_items")
public class CartItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "seat_id")
    private Long seatId;

    @Column(name = "ga_claim_id")
    private Long gaClaimId;

    @Column(name = "pricing_tier_id", nullable = false)
    private Long pricingTierId;

    @Column(name = "eb_ticket_class_id", nullable = false)
    private String ebTicketClassId;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount", column = @Column(name = "base_price_amount", nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false))
    })
    private Money basePrice;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    protected CartItem() {
    }

    public CartItem(Long seatId, Long gaClaimId, Long pricingTierId, String ebTicketClassId, Money basePrice, Integer quantity) {
        if ((seatId == null && gaClaimId == null) || (seatId != null && gaClaimId != null)) {
            throw new BusinessRuleException("Cart item must reference either seat or GA claim", "INVALID_CART_ITEM_MODE");
        }
        this.seatId = seatId;
        this.gaClaimId = gaClaimId;
        this.pricingTierId = pricingTierId;
        this.ebTicketClassId = ebTicketClassId;
        this.basePrice = basePrice;
        this.quantity = quantity;
    }

    void attachTo(Cart cart) {
        this.cart = cart;
    }

    void detach() {
        this.cart = null;
    }

    public Cart getCart() {
        return cart;
    }

    public Long getSeatId() {
        return seatId;
    }

    public Long getGaClaimId() {
        return gaClaimId;
    }

    public Long getPricingTierId() {
        return pricingTierId;
    }

    public String getEbTicketClassId() {
        return ebTicketClassId;
    }

    public Money getBasePrice() {
        return basePrice;
    }

    public Integer getQuantity() {
        return quantity;
    }
}
