package com.eventplatform.promotions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.promotions.domain.Coupon;
import com.eventplatform.promotions.domain.DiscountReconciliationLog;
import com.eventplatform.promotions.domain.Promotion;
import com.eventplatform.promotions.domain.enums.DiscountType;
import com.eventplatform.promotions.domain.enums.EbSyncStatus;
import com.eventplatform.promotions.domain.enums.PromotionScope;
import com.eventplatform.promotions.repository.CouponRepository;
import com.eventplatform.promotions.repository.DiscountReconciliationLogRepository;
import com.eventplatform.promotions.repository.OrphanEbDiscountRepository;
import com.eventplatform.shared.eventbrite.dto.response.EbDiscountResponse;
import com.eventplatform.shared.eventbrite.service.EbDiscountSyncService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiscountReconciliationServiceTest {

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private OrphanEbDiscountRepository orphanRepository;
    @Mock
    private DiscountReconciliationLogRepository logRepository;
    @Mock
    private EbDiscountSyncService ebDiscountSyncService;

    @InjectMocks
    private DiscountReconciliationService service;

    private Coupon coupon;

    @BeforeEach
    void setUp() {
        Promotion promotion = new Promotion(
            1L, "Promo", DiscountType.PERCENT_OFF, new BigDecimal("10"), PromotionScope.ORG_WIDE,
            null, 10, 2, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600)
        );
        coupon = new Coupon(promotion, 1L, "SAVE10");
        coupon.markSynced("eb_1", 0);
    }

    @Test
    void reconciliation_should_update_internal_redemption_count_when_EB_quantity_sold_is_higher() {
        coupon.recordRedemption(100);
        coupon.recordRedemption(100);
        when(couponRepository.findByOrgIdAndEbSyncStatus(1L, EbSyncStatus.SYNCED)).thenReturn(List.of(coupon));
        when(ebDiscountSyncService.listDiscounts("1"))
            .thenReturn(List.of(new EbDiscountResponse("eb_1", "SAVE10", "percent", 10.0, null, 3, 10, null)));

        service.reconcileOrg(1L);

        assertThat(coupon.getRedemptionCount()).isEqualTo(3);
        assertThat(coupon.getEbQuantitySoldAtLastSync()).isEqualTo(3);
        ArgumentCaptor<DiscountReconciliationLog> logCaptor = ArgumentCaptor.forClass(DiscountReconciliationLog.class);
        verify(logRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getDriftsFound()).isEqualTo(1);
    }

    @Test
    void reconciliation_should_create_OrphanEbDiscount_when_no_internal_match_found() {
        when(couponRepository.findByOrgIdAndEbSyncStatus(1L, EbSyncStatus.SYNCED)).thenReturn(List.of(coupon));
        when(ebDiscountSyncService.listDiscounts("1"))
            .thenReturn(List.of(new EbDiscountResponse("orphan_id", "ORPHAN", "percent", 10.0, null, 0, 10, null)));

        service.reconcileOrg(1L);

        verify(orphanRepository).save(any());
        ArgumentCaptor<DiscountReconciliationLog> logCaptor = ArgumentCaptor.forClass(DiscountReconciliationLog.class);
        verify(logRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getOrphansFound()).isEqualTo(1);
    }

    @Test
    void reconciliation_should_set_EB_DELETED_EXTERNALLY_when_synced_coupon_missing_from_EB_list() {
        when(couponRepository.findByOrgIdAndEbSyncStatus(1L, EbSyncStatus.SYNCED)).thenReturn(List.of(coupon));
        when(ebDiscountSyncService.listDiscounts("1")).thenReturn(List.of());

        service.reconcileOrg(1L);

        assertThat(coupon.getEbSyncStatus()).isEqualTo(EbSyncStatus.EB_DELETED_EXTERNALLY);
        ArgumentCaptor<DiscountReconciliationLog> logCaptor = ArgumentCaptor.forClass(DiscountReconciliationLog.class);
        verify(logRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getExternallyDeletedFound()).isEqualTo(1);
    }

    @Test
    void reconciliation_should_set_DRIFT_DETECTED_when_EB_fields_differ_from_internal() {
        when(couponRepository.findByOrgIdAndEbSyncStatus(1L, EbSyncStatus.SYNCED)).thenReturn(List.of(coupon));
        when(ebDiscountSyncService.listDiscounts("1"))
            .thenReturn(List.of(new EbDiscountResponse("eb_1", "SAVE20", "percent", 10.0, null, 0, 10, null)));

        service.reconcileOrg(1L);

        assertThat(coupon.getEbSyncStatus()).isEqualTo(EbSyncStatus.DRIFT_DETECTED);
        assertThat(coupon.getCode()).isEqualTo("SAVE10");
    }

    @Test
    void reconciliation_should_skip_org_and_log_on_EB_list_call_failure_without_aborting_other_orgs() {
        when(couponRepository.findDistinctOrgIdByEbSyncStatus(EbSyncStatus.SYNCED)).thenReturn(List.of(1L, 2L));
        when(couponRepository.findByOrgIdAndEbSyncStatus(1L, EbSyncStatus.SYNCED)).thenReturn(List.of(coupon));
        when(couponRepository.findByOrgIdAndEbSyncStatus(2L, EbSyncStatus.SYNCED)).thenReturn(List.of(coupon));
        when(ebDiscountSyncService.listDiscounts("1")).thenThrow(new RuntimeException("eb down"));
        when(ebDiscountSyncService.listDiscounts("2"))
            .thenReturn(List.of(new EbDiscountResponse("eb_1", "SAVE10", "percent", 10.0, null, 0, 10, null)));

        service.reconcileAllOrgs();

        verify(logRepository, times(2)).save(any());
    }
}
