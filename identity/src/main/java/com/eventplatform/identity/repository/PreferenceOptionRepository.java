package com.eventplatform.identity.repository;

import com.eventplatform.identity.domain.PreferenceOption;
import com.eventplatform.identity.domain.enums.PreferenceOptionType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreferenceOptionRepository extends JpaRepository<PreferenceOption, Long> {

  List<PreferenceOption> findByActiveTrueOrderByTypeAscSortOrderAscValueAsc();

  Optional<PreferenceOption> findByIdAndTypeAndActiveTrue(Long id, PreferenceOptionType type);

  List<PreferenceOption> findByIdInAndTypeAndActiveTrue(
      Collection<Long> ids, PreferenceOptionType type);
}
