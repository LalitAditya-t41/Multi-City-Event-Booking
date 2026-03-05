package com.eventplatform.promotions.repository;

import com.eventplatform.promotions.domain.CouponRedemption;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, Long> {

  long countByCouponIdAndUserIdAndVoidedFalse(Long couponId, Long userId);

  long countByCouponIdAndVoidedTrue(Long couponId);

  boolean existsByCouponIdAndVoidedFalse(Long couponId);

  boolean existsByCouponIdAndBookingIdAndVoidedFalse(Long couponId, Long bookingId);

  Optional<CouponRedemption> findByBookingIdAndVoidedFalse(Long bookingId);

  boolean existsByCouponIdInAndVoidedFalse(Collection<Long> couponIds);
}
