package com.eventplatform.paymentsticketing.repository;

import com.eventplatform.paymentsticketing.domain.CancellationRequest;
import com.eventplatform.paymentsticketing.domain.enums.CancellationRequestStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CancellationRequestRepository extends JpaRepository<CancellationRequest, Long> {

    Optional<CancellationRequest> findTopByBookingIdOrderByRequestedAtDesc(Long bookingId);

    boolean existsByBookingItemIdAndStatus(Long bookingItemId, CancellationRequestStatus status);

    List<CancellationRequest> findByBookingIdAndStatus(Long bookingId, CancellationRequestStatus status);
}
