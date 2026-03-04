package com.eventplatform.paymentsticketing.repository;

import com.eventplatform.paymentsticketing.domain.EventCancellationRefundAudit;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventCancellationRefundAuditRepository extends JpaRepository<EventCancellationRefundAudit, Long> {

    Optional<EventCancellationRefundAudit> findBySlotIdAndBookingId(Long slotId, Long bookingId);
}
