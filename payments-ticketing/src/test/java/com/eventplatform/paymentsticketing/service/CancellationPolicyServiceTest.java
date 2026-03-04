package com.eventplatform.paymentsticketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.paymentsticketing.api.dto.response.CancellationPolicyResponse;
import com.eventplatform.paymentsticketing.domain.Booking;
import com.eventplatform.paymentsticketing.domain.CancellationPolicy;
import com.eventplatform.paymentsticketing.domain.CancellationPolicyTier;
import com.eventplatform.paymentsticketing.domain.enums.CancellationPolicyScope;
import com.eventplatform.paymentsticketing.mapper.CancellationPolicyMapper;
import com.eventplatform.paymentsticketing.repository.CancellationPolicyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CancellationPolicyServiceTest {

    @Mock
    private CancellationPolicyRepository cancellationPolicyRepository;
    @Mock
    private CancellationPolicyMapper cancellationPolicyMapper;

    private CancellationPolicyService cancellationPolicyService;

    @BeforeEach
    void setUp() {
        cancellationPolicyService = new CancellationPolicyService(cancellationPolicyRepository, cancellationPolicyMapper);
    }

    @Test
    void should_resolve_org_policy_when_org_specific_policy_exists() {
        // Arrange
        CancellationPolicy orgPolicy = policy(88L, CancellationPolicyScope.ORG,
            List.of(new CancellationPolicyTier(24, 50, 1), new CancellationPolicyTier(null, 0, 2)));

        when(cancellationPolicyRepository.findByOrgId(88L)).thenReturn(Optional.of(orgPolicy));

        Booking booking = booking(88L, Instant.now().plusSeconds(30 * 3600));

        // Act
        RefundCalculationResult result = cancellationPolicyService.calculateRefund(booking, 100000L);

        // Assert
        assertThat(result.refundPercent()).isEqualTo(50);
        assertThat(result.refundAmountInSmallestUnit()).isEqualTo(50000L);
        verify(cancellationPolicyRepository, never()).findByScope(CancellationPolicyScope.SYSTEM_DEFAULT);
    }

    @Test
    void should_resolve_system_default_policy_when_org_policy_not_found() {
        // Arrange
        CancellationPolicy defaultPolicy = policy(null, CancellationPolicyScope.SYSTEM_DEFAULT,
            List.of(new CancellationPolicyTier(72, 100, 1), new CancellationPolicyTier(24, 50, 2), new CancellationPolicyTier(null, 0, 3)));

        when(cancellationPolicyRepository.findByOrgId(99L)).thenReturn(Optional.empty());
        when(cancellationPolicyRepository.findByScope(CancellationPolicyScope.SYSTEM_DEFAULT)).thenReturn(Optional.of(defaultPolicy));

        Booking booking = booking(99L, Instant.now().plusSeconds(26 * 3600));

        // Act
        RefundCalculationResult result = cancellationPolicyService.calculateRefund(booking, 200000L);

        // Assert
        assertThat(result.refundPercent()).isEqualTo(50);
        assertThat(result.refundAmountInSmallestUnit()).isEqualTo(100000L);
    }

    @Test
    void should_return_hardcoded_full_refund_fallback_when_no_policy_exists() {
        // Arrange
        when(cancellationPolicyRepository.findByOrgId(101L)).thenReturn(Optional.empty());
        when(cancellationPolicyRepository.findByScope(CancellationPolicyScope.SYSTEM_DEFAULT)).thenReturn(Optional.empty());

        Booking booking = booking(101L, Instant.now().plusSeconds(4 * 3600));

        // Act
        RefundCalculationResult result = cancellationPolicyService.calculateRefund(booking, 12345L);

        // Assert
        assertThat(result.refundPercent()).isEqualTo(100);
        assertThat(result.refundAmountInSmallestUnit()).isEqualTo(12345L);
    }

    @Test
    void should_compute_refund_amount_with_floor_rounding_when_policy_percent_has_fractional_result() {
        // Arrange
        CancellationPolicy defaultPolicy = policy(null, CancellationPolicyScope.SYSTEM_DEFAULT,
            List.of(new CancellationPolicyTier(1, 50, 1), new CancellationPolicyTier(null, 0, 2)));
        when(cancellationPolicyRepository.findByOrgId(88L)).thenReturn(Optional.empty());
        when(cancellationPolicyRepository.findByScope(CancellationPolicyScope.SYSTEM_DEFAULT)).thenReturn(Optional.of(defaultPolicy));

        Booking booking = booking(88L, Instant.now().plusSeconds(2 * 3600));

        // Act
        RefundCalculationResult result = cancellationPolicyService.calculateRefund(booking, 999L);

        // Assert
        assertThat(result.refundPercent()).isEqualTo(50);
        assertThat(result.refundAmountInSmallestUnit()).isEqualTo(499L);
    }

    @Test
    void should_return_effective_policy_response_when_fetching_effective_policy() {
        // Arrange
        CancellationPolicy defaultPolicy = policy(null, CancellationPolicyScope.SYSTEM_DEFAULT,
            List.of(new CancellationPolicyTier(24, 50, 1), new CancellationPolicyTier(null, 0, 2)));
        CancellationPolicyResponse mapped = new CancellationPolicyResponse(1L, null, CancellationPolicyScope.SYSTEM_DEFAULT, null, List.of());

        when(cancellationPolicyRepository.findByOrgId(500L)).thenReturn(Optional.empty());
        when(cancellationPolicyRepository.findByScope(CancellationPolicyScope.SYSTEM_DEFAULT)).thenReturn(Optional.of(defaultPolicy));
        when(cancellationPolicyMapper.toResponse(defaultPolicy)).thenReturn(mapped);

        // Act
        CancellationPolicyResponse response = cancellationPolicyService.getEffectivePolicy(500L);

        // Assert
        assertThat(response.scope()).isEqualTo(CancellationPolicyScope.SYSTEM_DEFAULT);
    }

    private Booking booking(Long orgId, Instant slotStartTime) {
        return new Booking(
            "BK-TEST-1",
            1L,
            2L,
            3L,
            4L,
            orgId,
            slotStartTime,
            1000L,
            "inr"
        );
    }

    private CancellationPolicy policy(Long orgId, CancellationPolicyScope scope, List<CancellationPolicyTier> tiers) {
        CancellationPolicy policy = new CancellationPolicy(orgId, scope, 10L);
        policy.replaceTiers(tiers);
        return policy;
    }
}
