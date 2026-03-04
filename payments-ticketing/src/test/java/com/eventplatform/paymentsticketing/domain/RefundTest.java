package com.eventplatform.paymentsticketing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventplatform.paymentsticketing.domain.enums.RefundReason;
import com.eventplatform.paymentsticketing.domain.enums.RefundCancellationType;
import com.eventplatform.paymentsticketing.domain.enums.RefundStatus;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

class RefundTest {

    @Test
    void should_transition_pending_to_succeeded_when_update_status_called() {
        Refund refund = new Refund(
            1L,
            "re_123",
            1000L,
            "inr",
            RefundReason.REQUESTED_BY_CUSTOMER,
            RefundStatus.PENDING,
            RefundCancellationType.BUYER_FULL
        );

        refund.updateStatus(RefundStatus.SUCCEEDED);

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
    }

    @Test
    void should_transition_pending_to_failed_when_update_status_called() {
        Refund refund = new Refund(
            1L,
            "re_123",
            1000L,
            "inr",
            RefundReason.REQUESTED_BY_CUSTOMER,
            RefundStatus.PENDING,
            RefundCancellationType.BUYER_FULL
        );

        refund.updateStatus(RefundStatus.FAILED);

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
    }

    @Test
    void should_throw_when_transitioning_succeeded_to_failed() {
        Refund refund = new Refund(
            1L,
            "re_123",
            1000L,
            "inr",
            RefundReason.REQUESTED_BY_CUSTOMER,
            RefundStatus.SUCCEEDED,
            RefundCancellationType.BUYER_FULL
        );

        assertThatThrownBy(() -> refund.updateStatus(RefundStatus.FAILED))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("terminal");
    }
}
