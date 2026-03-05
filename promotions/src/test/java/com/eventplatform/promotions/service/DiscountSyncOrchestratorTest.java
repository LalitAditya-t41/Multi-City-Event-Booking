package com.eventplatform.promotions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.promotions.domain.Coupon;
import com.eventplatform.promotions.domain.Promotion;
import com.eventplatform.promotions.domain.enums.DiscountType;
import com.eventplatform.promotions.domain.enums.EbSyncStatus;
import com.eventplatform.promotions.domain.enums.PromotionScope;
import com.eventplatform.promotions.mapper.EbDiscountSyncPayloadMapper;
import com.eventplatform.promotions.repository.CouponRepository;
import com.eventplatform.shared.eventbrite.dto.request.EbDiscountCreateRequest;
import com.eventplatform.shared.eventbrite.dto.response.EbDiscountResponse;
import com.eventplatform.shared.eventbrite.service.EbDiscountSyncService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DiscountSyncOrchestratorTest {

  @Mock private CouponRepository couponRepository;
  @Mock private EbDiscountSyncService ebDiscountSyncService;
  @Mock private EbDiscountSyncPayloadMapper payloadMapper;

  @InjectMocks private DiscountSyncOrchestrator orchestrator;

  private Coupon coupon;

  @BeforeEach
  void setUp() {
    Promotion promotion =
        new Promotion(
            1L,
            "Promo",
            DiscountType.PERCENT_OFF,
            new BigDecimal("10"),
            PromotionScope.ORG_WIDE,
            null,
            10,
            2,
            Instant.now().minusSeconds(3600),
            Instant.now().plusSeconds(3600));
    coupon = new Coupon(promotion, 1L, "SAVE10");
  }

  @Test
  void createSync_should_call_EbDiscountSyncService_and_store_eb_discount_id_on_success() {
    when(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon));
    when(ebDiscountSyncService.listDiscounts("1")).thenReturn(List.of());
    when(payloadMapper.toRequest(any(), any()))
        .thenReturn(
            new EbDiscountCreateRequest("SAVE10", "percent", 10.0, null, null, null, 10, null));
    when(ebDiscountSyncService.createDiscount(any(), any()))
        .thenReturn(
            new EbDiscountResponse(
                "eb_discount_abc", "SAVE10", "percent", 10.0, null, 0, 10, null));
    when(ebDiscountSyncService.getDiscount("eb_discount_abc"))
        .thenReturn(
            new EbDiscountResponse(
                "eb_discount_abc", "SAVE10", "percent", 10.0, null, 0, 10, null));

    orchestrator.createSync(1L);

    assertThat(coupon.getEbDiscountId()).isEqualTo("eb_discount_abc");
    assertThat(coupon.getEbSyncStatus()).isEqualTo(EbSyncStatus.SYNCED);
  }

  @Test
  void createSync_should_set_SYNC_FAILED_when_EB_create_returns_error_and_not_block_internal() {
    when(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon));
    when(ebDiscountSyncService.listDiscounts("1")).thenReturn(List.of());
    when(payloadMapper.toRequest(any(), any()))
        .thenReturn(
            new EbDiscountCreateRequest("SAVE10", "percent", 10.0, null, null, null, 10, null));
    when(ebDiscountSyncService.createDiscount(any(), any()))
        .thenThrow(new RuntimeException("boom"));

    assertThatCode(() -> orchestrator.createSync(1L)).doesNotThrowAnyException();

    assertThat(coupon.getEbSyncStatus()).isEqualTo(EbSyncStatus.SYNC_FAILED);
  }

  @Test
  void modifySync_should_delete_then_recreate_on_EB_when_quantity_sold_is_zero() {
    coupon.markSynced("old_id", 0);
    when(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon));
    when(ebDiscountSyncService.getDiscount("old_id"))
        .thenReturn(new EbDiscountResponse("old_id", "SAVE10", "percent", 10.0, null, 0, 10, null));
    when(ebDiscountSyncService.listDiscounts("1")).thenReturn(List.of());
    when(payloadMapper.toRequest(any(), any()))
        .thenReturn(
            new EbDiscountCreateRequest("SAVE10", "percent", 10.0, null, null, null, 10, null));
    when(ebDiscountSyncService.createDiscount(any(), any()))
        .thenReturn(new EbDiscountResponse("new_id", "SAVE10", "percent", 10.0, null, 0, 10, null));
    when(ebDiscountSyncService.getDiscount("new_id"))
        .thenReturn(new EbDiscountResponse("new_id", "SAVE10", "percent", 10.0, null, 0, 10, null));

    orchestrator.resyncAfterPromotionUpdate(1L);

    assertThat(coupon.getEbDiscountId()).isEqualTo("new_id");
    assertThat(coupon.getEbSyncStatus()).isEqualTo(EbSyncStatus.SYNCED);
    verify(ebDiscountSyncService).deleteDiscount("old_id");
  }

  @Test
  void modifySync_should_set_CANNOT_RESYNC_when_quantity_sold_is_greater_than_zero() {
    coupon.markSynced("old_id", 0);
    when(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon));
    when(ebDiscountSyncService.getDiscount("old_id"))
        .thenReturn(new EbDiscountResponse("old_id", "SAVE10", "percent", 10.0, null, 3, 10, null));

    orchestrator.resyncAfterPromotionUpdate(1L);

    assertThat(coupon.getEbSyncStatus()).isEqualTo(EbSyncStatus.CANNOT_RESYNC);
    verify(ebDiscountSyncService, never()).deleteDiscount(any());
  }

  @Test
  void deleteSync_should_call_EB_delete_when_quantity_sold_is_zero() {
    coupon.markSynced("eb_123", 0);
    when(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon));
    when(ebDiscountSyncService.getDiscount("eb_123"))
        .thenReturn(new EbDiscountResponse("eb_123", "SAVE10", "percent", 10.0, null, 0, 10, null));

    orchestrator.guardedDelete(1L);

    verify(ebDiscountSyncService).deleteDiscount("eb_123");
    assertThat(coupon.getEbDiscountId()).isNull();
    assertThat(coupon.getEbSyncStatus()).isEqualTo(EbSyncStatus.NOT_SYNCED);
  }

  @Test
  void deleteSync_should_set_DELETE_BLOCKED_when_EB_responds_DISCOUNT_CANNOT_BE_DELETED() {
    coupon.markSynced("eb_123", 0);
    when(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon));
    when(ebDiscountSyncService.getDiscount("eb_123"))
        .thenReturn(new EbDiscountResponse("eb_123", "SAVE10", "percent", 10.0, null, 2, 10, null));

    orchestrator.guardedDelete(1L);

    verify(ebDiscountSyncService, never()).deleteDiscount(any());
    assertThat(coupon.getEbSyncStatus()).isEqualTo(EbSyncStatus.DELETE_BLOCKED);
  }

  @Test
  void
      createSync_should_adopt_existing_eb_discount_id_when_idempotency_guard_finds_matching_code() {
    when(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon));
    when(ebDiscountSyncService.listDiscounts("1"))
        .thenReturn(
            List.of(
                new EbDiscountResponse(
                    "existing_1", "SAVE10", "percent", 10.0, null, 0, 10, null)));
    when(ebDiscountSyncService.getDiscount("existing_1"))
        .thenReturn(
            new EbDiscountResponse("existing_1", "SAVE10", "percent", 10.0, null, 0, 10, null));

    orchestrator.createSync(1L);

    assertThat(coupon.getEbDiscountId()).isEqualTo("existing_1");
    assertThat(coupon.getEbSyncStatus()).isEqualTo(EbSyncStatus.SYNCED);
    verify(ebDiscountSyncService, never()).createDiscount(any(), any());
  }

  @Test
  void createSync_should_skip_EB_and_log_warning_when_eb_event_id_is_null_on_event_scoped_coupon() {
    Promotion eventScoped =
        new Promotion(
            1L,
            "Promo",
            DiscountType.PERCENT_OFF,
            new BigDecimal("10"),
            PromotionScope.EVENT_SCOPED,
            null,
            10,
            2,
            Instant.now().minusSeconds(3600),
            Instant.now().plusSeconds(3600));
    Coupon scopedCoupon = new Coupon(eventScoped, 1L, "SAVE10");
    when(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(scopedCoupon));

    orchestrator.createSync(1L);

    verify(ebDiscountSyncService, never()).listDiscounts(any());
    verify(ebDiscountSyncService, never()).createDiscount(any(), any());
    assertThat(scopedCoupon.getEbSyncStatus()).isEqualTo(EbSyncStatus.NOT_SYNCED);
  }
}
