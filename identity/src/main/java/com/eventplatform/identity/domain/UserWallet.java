package com.eventplatform.identity.domain;

import com.eventplatform.shared.common.domain.BaseEntity;
import com.eventplatform.shared.common.domain.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "user_wallets")
public class UserWallet extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount", column = @Column(name = "balance_amount", nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false))
    })
    private Money balance;

    protected UserWallet() {
    }

    public UserWallet(User user) {
        this.user = user;
        this.balance = new Money(BigDecimal.ZERO, "INR");
    }

    public User getUser() {
        return user;
    }

    public Money getBalance() {
        return balance;
    }
}
