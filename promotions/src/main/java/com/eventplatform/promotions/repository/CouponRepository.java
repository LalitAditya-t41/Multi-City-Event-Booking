package com.eventplatform.promotions.repository;

import com.eventplatform.promotions.domain.Coupon;
import com.eventplatform.promotions.domain.enums.CouponStatus;
import com.eventplatform.promotions.domain.enums.EbSyncStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCodeIgnoreCaseAndOrgId(String code, Long orgId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.id = :couponId")
    Optional<Coupon> findByIdForUpdate(@Param("couponId") Long couponId);

    @Query("select c from Coupon c where lower(c.code) = lower(:code) and c.orgId = :orgId "
        + "and c.status <> com.eventplatform.promotions.domain.enums.CouponStatus.INACTIVE")
    Optional<Coupon> findActiveOrExhaustedByCodeAndOrg(@Param("code") String code, @Param("orgId") Long orgId);

    boolean existsByCodeIgnoreCaseAndOrgIdAndStatusNot(String code, Long orgId, CouponStatus status);

    List<Coupon> findByPromotionId(Long promotionId);

    List<Coupon> findByStatusAndEbSyncStatus(CouponStatus status, EbSyncStatus ebSyncStatus);

    @Query("select distinct c.orgId from Coupon c where c.ebSyncStatus = :status")
    List<Long> findDistinctOrgIdByEbSyncStatus(@Param("status") EbSyncStatus status);

    @Query("select c from Coupon c join c.promotion p where c.status = 'ACTIVE' and p.validUntil < :now")
    List<Coupon> findExpiredActiveCoupons(@Param("now") Instant now);

    List<Coupon> findByOrgIdAndEbSyncStatus(Long orgId, EbSyncStatus status);

    Optional<Coupon> findTopByCodeIgnoreCaseAndOrgId(String code, Long orgId);
}
