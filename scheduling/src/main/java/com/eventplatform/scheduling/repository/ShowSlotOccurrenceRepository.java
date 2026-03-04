package com.eventplatform.scheduling.repository;

import com.eventplatform.scheduling.domain.ShowSlotOccurrence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowSlotOccurrenceRepository extends JpaRepository<ShowSlotOccurrence, Long> {
    List<ShowSlotOccurrence> findByParentSlotIdOrderByOccurrenceIndexAsc(Long parentSlotId);
}
