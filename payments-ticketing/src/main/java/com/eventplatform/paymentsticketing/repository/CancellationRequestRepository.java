package com.eventplatform.paymentsticketing.repository;

import com.eventplatform.paymentsticketing.domain.CancellationRequest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CancellationRequestRepository extends JpaRepository<CancellationRequest, Long> {

    Optional<CancellationRequest> findTopByBookingIdOrderByRequestedAtDesc(Long bookingId);
}
