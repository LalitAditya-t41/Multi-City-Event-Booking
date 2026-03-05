package com.eventplatform.bookinginventory.repository;

import com.eventplatform.bookinginventory.domain.SeatLockAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatLockAuditLogRepository extends JpaRepository<SeatLockAuditLog, Long> {}
