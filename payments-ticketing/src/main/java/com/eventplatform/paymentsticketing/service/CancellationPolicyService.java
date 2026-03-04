package com.eventplatform.paymentsticketing.service;

import com.eventplatform.paymentsticketing.api.dto.request.CancellationPolicyRequest;
import com.eventplatform.paymentsticketing.api.dto.response.CancellationPolicyResponse;
import com.eventplatform.paymentsticketing.domain.Booking;
import com.eventplatform.paymentsticketing.domain.CancellationPolicy;
import com.eventplatform.paymentsticketing.domain.CancellationPolicyTier;
import com.eventplatform.paymentsticketing.domain.enums.CancellationPolicyScope;
import com.eventplatform.paymentsticketing.exception.CancellationPolicyNotFoundException;
import com.eventplatform.paymentsticketing.exception.DuplicateOrgPolicyException;
import com.eventplatform.paymentsticketing.exception.InvalidPolicyTierConfigException;
import com.eventplatform.paymentsticketing.mapper.CancellationPolicyMapper;
import com.eventplatform.paymentsticketing.repository.CancellationPolicyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancellationPolicyService {

    private final CancellationPolicyRepository cancellationPolicyRepository;
    private final CancellationPolicyMapper cancellationPolicyMapper;

    public CancellationPolicyService(
        CancellationPolicyRepository cancellationPolicyRepository,
        CancellationPolicyMapper cancellationPolicyMapper
    ) {
        this.cancellationPolicyRepository = cancellationPolicyRepository;
        this.cancellationPolicyMapper = cancellationPolicyMapper;
    }

    @Transactional
    public CancellationPolicyResponse createPolicy(Long adminUserId, CancellationPolicyRequest request) {
        validateTiers(request.tiers());
        if (request.orgId() != null && cancellationPolicyRepository.existsByOrgId(request.orgId())) {
            throw new DuplicateOrgPolicyException(request.orgId());
        }

        CancellationPolicy policy = new CancellationPolicy(
            request.orgId(),
            request.orgId() == null ? CancellationPolicyScope.SYSTEM_DEFAULT : CancellationPolicyScope.ORG,
            adminUserId
        );
        policy.replaceTiers(toDomainTiers(request.tiers()));
        return cancellationPolicyMapper.toResponse(cancellationPolicyRepository.save(policy));
    }

    @Transactional
    public CancellationPolicyResponse updatePolicy(Long policyId, CancellationPolicyRequest request) {
        validateTiers(request.tiers());

        CancellationPolicy policy = cancellationPolicyRepository.findById(policyId)
            .orElseThrow(() -> new CancellationPolicyNotFoundException("Cancellation policy not found: " + policyId));

        if (!Objects.equals(policy.getOrgId(), request.orgId())) {
            throw new InvalidPolicyTierConfigException("orgId cannot be changed for an existing policy");
        }

        policy.replaceTiers(toDomainTiers(request.tiers()));
        return cancellationPolicyMapper.toResponse(policy);
    }

    @Transactional(readOnly = true)
    public CancellationPolicyResponse getPolicy(Long policyId) {
        CancellationPolicy policy = cancellationPolicyRepository.findById(policyId)
            .orElseThrow(() -> new CancellationPolicyNotFoundException("Cancellation policy not found: " + policyId));
        return cancellationPolicyMapper.toResponse(policy);
    }

    @Transactional(readOnly = true)
    public CancellationPolicyResponse getEffectivePolicy(Long orgId) {
        return cancellationPolicyMapper.toResponse(resolveEffectivePolicy(orgId));
    }

    @Transactional(readOnly = true)
    public RefundCalculationResult calculateRefund(Booking booking, long baseAmountInSmallestUnit) {
        int fallbackPercent = 100;
        if (booking.getSlotStartTime() == null) {
            long fallbackAmount = (baseAmountInSmallestUnit * fallbackPercent) / 100;
            return new RefundCalculationResult(fallbackPercent, fallbackAmount, "FALLBACK_100");
        }

        CancellationPolicy policy = resolveEffectivePolicy(booking.getOrgId());
        List<CancellationPolicyTier> tiers = policy.getTiers().stream()
            .sorted(Comparator.comparing(CancellationPolicyTier::getSortOrder))
            .toList();
        if (tiers.isEmpty()) {
            long fallbackAmount = (baseAmountInSmallestUnit * fallbackPercent) / 100;
            return new RefundCalculationResult(fallbackPercent, fallbackAmount, "FALLBACK_100");
        }

        long hoursUntilEvent = Duration.between(Instant.now(), booking.getSlotStartTime()).toHours();
        CancellationPolicyTier matchedTier = tiers.stream()
            .filter(tier -> tier.getHoursBeforeEvent() == null || hoursUntilEvent >= tier.getHoursBeforeEvent())
            .findFirst()
            .orElse(tiers.getLast());

        int refundPercent = matchedTier.getRefundPercent();
        long refundAmount = (baseAmountInSmallestUnit * refundPercent) / 100;

        String tierLabel = matchedTier.getHoursBeforeEvent() == null
            ? "DEFAULT_TIER"
            : "GE_" + matchedTier.getHoursBeforeEvent() + "_HOURS";

        return new RefundCalculationResult(refundPercent, refundAmount, tierLabel);
    }

    private CancellationPolicy resolveEffectivePolicy(Long orgId) {
        if (orgId != null) {
            CancellationPolicy orgPolicy = cancellationPolicyRepository.findByOrgId(orgId).orElse(null);
            if (orgPolicy != null) {
                return orgPolicy;
            }
        }

        return cancellationPolicyRepository.findByScope(CancellationPolicyScope.SYSTEM_DEFAULT)
            .orElseGet(() -> {
                CancellationPolicy fallback = new CancellationPolicy(null, CancellationPolicyScope.SYSTEM_DEFAULT, null);
                fallback.replaceTiers(List.of(new CancellationPolicyTier(null, 100, 1)));
                return fallback;
            });
    }

    private List<CancellationPolicyTier> toDomainTiers(List<CancellationPolicyRequest.TierRequest> tiers) {
        return tiers.stream()
            .map(tier -> new CancellationPolicyTier(tier.hoursBeforeEvent(), tier.refundPercent(), tier.sortOrder()))
            .toList();
    }

    private void validateTiers(List<CancellationPolicyRequest.TierRequest> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new InvalidPolicyTierConfigException("At least one policy tier is required");
        }

        List<CancellationPolicyRequest.TierRequest> sorted = tiers.stream()
            .sorted(Comparator.comparing(CancellationPolicyRequest.TierRequest::sortOrder))
            .toList();

        Integer previousSortOrder = null;
        Integer previousHoursBeforeEvent = null;
        boolean seenNullThreshold = false;

        for (CancellationPolicyRequest.TierRequest tier : sorted) {
            if (tier.refundPercent() < 0 || tier.refundPercent() > 100) {
                throw new InvalidPolicyTierConfigException("refundPercent must be between 0 and 100");
            }
            if (tier.sortOrder() < 1) {
                throw new InvalidPolicyTierConfigException("sortOrder must be >= 1");
            }
            if (previousSortOrder != null && previousSortOrder.equals(tier.sortOrder())) {
                throw new InvalidPolicyTierConfigException("sortOrder values must be unique");
            }
            if (seenNullThreshold) {
                throw new InvalidPolicyTierConfigException("Tier with null hoursBeforeEvent must be the last tier");
            }
            if (tier.hoursBeforeEvent() == null) {
                seenNullThreshold = true;
            } else {
                if (tier.hoursBeforeEvent() < 0) {
                    throw new InvalidPolicyTierConfigException("hoursBeforeEvent must be >= 0");
                }
                if (previousHoursBeforeEvent != null && tier.hoursBeforeEvent() > previousHoursBeforeEvent) {
                    throw new InvalidPolicyTierConfigException(
                        "hoursBeforeEvent must be ordered from larger to smaller thresholds by sortOrder"
                    );
                }
                previousHoursBeforeEvent = tier.hoursBeforeEvent();
            }
            previousSortOrder = tier.sortOrder();
        }
    }
}
