package com.eventplatform.promotions.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.promotions.api.controller.CouponAdminController;
import com.eventplatform.promotions.api.controller.CouponUserController;
import com.eventplatform.promotions.api.controller.PromotionAdminController;
import com.eventplatform.promotions.api.dto.response.CouponResponse;
import com.eventplatform.promotions.api.dto.response.DiscountBreakdownResponse;
import com.eventplatform.promotions.api.dto.response.PromotionResponse;
import com.eventplatform.promotions.domain.enums.CouponStatus;
import com.eventplatform.promotions.domain.enums.DiscountType;
import com.eventplatform.promotions.domain.enums.EbSyncStatus;
import com.eventplatform.promotions.domain.enums.PromotionScope;
import com.eventplatform.promotions.domain.enums.PromotionStatus;
import com.eventplatform.promotions.exception.CouponCodeConflictException;
import com.eventplatform.promotions.exception.CouponInactiveException;
import com.eventplatform.promotions.exception.CouponUsageLimitReachedException;
import com.eventplatform.promotions.service.CouponAdminService;
import com.eventplatform.promotions.service.CouponEligibilityService;
import com.eventplatform.promotions.service.DiscountReconciliationService;
import com.eventplatform.promotions.service.PromotionService;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.JwtAuthenticationFilter;
import com.eventplatform.shared.security.SecurityConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = {
      PromotionAdminController.class,
      CouponAdminController.class,
      CouponUserController.class
    })
@ContextConfiguration(classes = com.eventplatform.promotions.PromotionsTestApplication.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class PromotionsControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PromotionService promotionService;
  @MockitoBean private CouponAdminService couponAdminService;
  @MockitoBean private CouponEligibilityService couponEligibilityService;
  @MockitoBean private DiscountReconciliationService reconciliationService;
  @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;

  @BeforeEach
  void setUpFilterPassThrough() throws Exception {
    doAnswer(
            invocation -> {
              jakarta.servlet.FilterChain chain = invocation.getArgument(2);
              chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
              return null;
            })
        .when(jwtAuthenticationFilter)
        .doFilter(any(), any(), any());
  }

  @Test
  void POST_promotions_should_return_201_with_promotion_id_for_ROLE_ADMIN() throws Exception {
    when(promotionService.create(any(), any()))
        .thenReturn(
            new PromotionResponse(
                101L,
                1L,
                "Promo",
                DiscountType.PERCENT_OFF,
                new BigDecimal("10"),
                PromotionScope.ORG_WIDE,
                null,
                10,
                2,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                PromotionStatus.ACTIVE,
                Instant.now(),
                Instant.now()));

    mockMvc
        .perform(
            post("/api/v1/promotions")
                .with(authentication(adminAuth()))
                .contentType("application/json")
                .content(
                    """
                    {
                      "name":"Promo",
                      "discountType":"PERCENT_OFF",
                      "discountValue":10,
                      "scope":"ORG_WIDE",
                      "validFrom":"2026-03-05T00:00:00Z",
                      "validUntil":"2026-03-06T00:00:00Z",
                      "maxUsageLimit":10,
                      "perUserCap":2
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(101))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void POST_promotions_should_return_403_when_called_by_ROLE_USER() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/promotions")
                .with(authentication(userAuth()))
                .contentType("application/json")
                .content(
                    """
                    {
                      "name":"Promo",
                      "discountType":"PERCENT_OFF",
                      "discountValue":10,
                      "scope":"ORG_WIDE",
                      "validFrom":"2026-03-05T00:00:00Z",
                      "validUntil":"2026-03-06T00:00:00Z"
                    }
                    """))
        .andExpect(status().isForbidden());
  }

  @Test
  void POST_coupons_should_return_201_and_show_SYNC_PENDING_eb_sync_status() throws Exception {
    when(couponAdminService.createCoupon(any(), any(), any()))
        .thenReturn(
            new CouponResponse(
                1L,
                101L,
                1L,
                "SAVE10",
                CouponStatus.ACTIVE,
                0,
                EbSyncStatus.SYNC_PENDING,
                null,
                null,
                Instant.now(),
                Instant.now()));

    mockMvc
        .perform(
            post("/api/v1/promotions/101/coupons")
                .with(authentication(adminAuth()))
                .contentType("application/json")
                .content("{" + "\"code\":\"SAVE10\"" + "}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.ebSyncStatus").value("SYNC_PENDING"));
  }

  @Test
  void POST_coupons_should_return_409_on_duplicate_code_within_org() throws Exception {
    when(couponAdminService.createCoupon(any(), any(), any()))
        .thenThrow(new CouponCodeConflictException("SAVE10"));

    mockMvc
        .perform(
            post("/api/v1/promotions/101/coupons")
                .with(authentication(adminAuth()))
                .contentType("application/json")
                .content("{" + "\"code\":\"SAVE10\"" + "}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("COUPON_CODE_CONFLICT"));
  }

  @Test
  void POST_promotions_validate_should_return_200_with_discount_breakdown_for_valid_coupon()
      throws Exception {
    when(couponEligibilityService.validateAndApply(any(), any()))
        .thenReturn(
            new DiscountBreakdownResponse("SAVE10", DiscountType.PERCENT_OFF, 1000L, 9000L, "inr"));

    mockMvc
        .perform(
            post("/api/v1/promotions/validate")
                .with(authentication(userAuth()))
                .contentType("application/json")
                .content("{" + "\"couponCode\":\"SAVE10\",\"cartId\":100" + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.discountAmountInSmallestUnit").value(1000))
        .andExpect(jsonPath("$.adjustedCartTotalInSmallestUnit").value(9000));
  }

  @Test
  void POST_promotions_validate_should_return_410_when_coupon_is_INACTIVE() throws Exception {
    when(couponEligibilityService.validateAndApply(any(), any()))
        .thenThrow(new CouponInactiveException("SAVE10"));

    mockMvc
        .perform(
            post("/api/v1/promotions/validate")
                .with(authentication(userAuth()))
                .contentType("application/json")
                .content("{" + "\"couponCode\":\"SAVE10\",\"cartId\":100" + "}"))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.errorCode").value("COUPON_INACTIVE"));
  }

  @Test
  void POST_promotions_validate_should_return_409_when_usage_limit_reached() throws Exception {
    when(couponEligibilityService.validateAndApply(any(), any()))
        .thenThrow(new CouponUsageLimitReachedException());

    mockMvc
        .perform(
            post("/api/v1/promotions/validate")
                .with(authentication(userAuth()))
                .contentType("application/json")
                .content("{" + "\"couponCode\":\"SAVE10\",\"cartId\":100" + "}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("COUPON_USAGE_LIMIT_REACHED"));
  }

  @Test
  void DELETE_promotions_cart_coupon_should_return_204_and_release_reservation() throws Exception {
    mockMvc
        .perform(delete("/api/v1/promotions/cart/100/coupon").with(authentication(userAuth())))
        .andExpect(status().isNoContent());
  }

  @Test
  void POST_coupons_sync_should_return_200_with_updated_ebSyncStatus() throws Exception {
    when(couponAdminService.manualSync(any(), any()))
        .thenReturn(
            new CouponResponse(
                1L,
                101L,
                1L,
                "SAVE10",
                CouponStatus.ACTIVE,
                1,
                EbSyncStatus.SYNCED,
                "eb_1",
                1,
                Instant.now(),
                Instant.now()));

    mockMvc
        .perform(post("/api/v1/promotions/coupons/SAVE10/sync").with(authentication(adminAuth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ebSyncStatus").value("SYNCED"));
  }

  @Test
  void POST_reconciliation_trigger_should_return_202_accepted() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/promotions/reconciliation/trigger").with(authentication(adminAuth())))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.message").value("Reconciliation triggered"));
  }

  private UsernamePasswordAuthenticationToken adminAuth() {
    return new UsernamePasswordAuthenticationToken(
        new AuthenticatedUser(1L, "ADMIN", 1L, "admin@test.com"),
        null,
        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
  }

  private UsernamePasswordAuthenticationToken userAuth() {
    return new UsernamePasswordAuthenticationToken(
        new AuthenticatedUser(99L, "USER", 1L, "user@test.com"),
        null,
        List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
