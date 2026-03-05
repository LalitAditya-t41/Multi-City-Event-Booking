package com.eventplatform.promotions.repository;

import com.eventplatform.promotions.domain.CouponUsageReservation;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponUsageReservationRepository
    extends JpaRepository<CouponUsageReservation, Long> {

  @Query(
      "select count(r) from CouponUsageReservation r where r.coupon.id = :couponId and r.released = false and r.expiresAt > :now")
  long countActiveByCouponId(@Param("couponId") Long couponId, @Param("now") Instant now);

  @Query(
      "select r from CouponUsageReservation r where r.coupon.id = :couponId and r.cartId = :cartId and r.released = false and r.expiresAt > :now")
  Optional<CouponUsageReservation> findActiveByCouponIdAndCartId(
      @Param("couponId") Long couponId, @Param("cartId") Long cartId, @Param("now") Instant now);

  @Query("select r from CouponUsageReservation r where r.cartId = :cartId and r.released = false")
  Optional<CouponUsageReservation> findOpenByCartId(@Param("cartId") Long cartId);

  @Modifying
  @Query(
      "update CouponUsageReservation r set r.released = true where r.coupon.id = :couponId and r.released = false")
  int releaseAllByCouponId(@Param("couponId") Long couponId);

  @Modifying
  @Query(
      "update CouponUsageReservation r set r.released = true where r.cartId = :cartId and r.userId = :userId and r.released = false")
  int releaseByCartIdAndUserId(@Param("cartId") Long cartId, @Param("userId") Long userId);

  @Query(
      "select count(r) from CouponUsageReservation r where r.coupon.id = :couponId and r.released = false and r.expiresAt > :now")
  long countOpenReservations(@Param("couponId") Long couponId, @Param("now") Instant now);
}
