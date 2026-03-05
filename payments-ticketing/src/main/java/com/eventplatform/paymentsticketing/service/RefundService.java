package com.eventplatform.paymentsticketing.service;

import com.eventplatform.paymentsticketing.domain.Refund;
import com.eventplatform.paymentsticketing.domain.enums.RefundStatus;
import com.eventplatform.paymentsticketing.repository.RefundRepository;
import com.eventplatform.shared.stripe.dto.StripeRefundResponse;
import com.eventplatform.shared.stripe.service.StripeRefundService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundService {

  private final RefundRepository refundRepository;
  private final CancellationService cancellationService;
  private final StripeRefundService stripeRefundService;

  public RefundService(
      RefundRepository refundRepository,
      CancellationService cancellationService,
      StripeRefundService stripeRefundService) {
    this.refundRepository = refundRepository;
    this.cancellationService = cancellationService;
    this.stripeRefundService = stripeRefundService;
  }

  @Transactional
  public void updateRefundStatus(String stripeRefundId) {
    Refund refund = refundRepository.findByStripeRefundId(stripeRefundId).orElse(null);
    if (refund == null) {
      return;
    }

    StripeRefundResponse stripeRefund = stripeRefundService.getRefund(stripeRefundId);
    RefundStatus nextStatus = toRefundStatus(stripeRefund.status());
    refund.updateStatus(nextStatus);

    if (nextStatus == RefundStatus.SUCCEEDED) {
      cancellationService.finaliseAfterRefund(refund.getBookingId());
    } else if (nextStatus == RefundStatus.FAILED) {
      cancellationService.handleRefundFailed(stripeRefundId);
    }
  }

  private RefundStatus toRefundStatus(String stripeStatus) {
    if ("succeeded".equalsIgnoreCase(stripeStatus)) {
      return RefundStatus.SUCCEEDED;
    }
    if ("failed".equalsIgnoreCase(stripeStatus) || "canceled".equalsIgnoreCase(stripeStatus)) {
      return RefundStatus.FAILED;
    }
    return RefundStatus.PENDING;
  }
}
