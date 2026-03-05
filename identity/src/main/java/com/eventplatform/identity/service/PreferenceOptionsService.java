package com.eventplatform.identity.service;

import com.eventplatform.identity.api.dto.response.PreferenceOptionItemResponse;
import com.eventplatform.identity.api.dto.response.PreferenceOptionsResponse;
import com.eventplatform.identity.mapper.PreferenceOptionMapper;
import com.eventplatform.identity.repository.PreferenceOptionRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PreferenceOptionsService {

  private final PreferenceOptionRepository preferenceOptionRepository;
  private final PreferenceOptionMapper preferenceOptionMapper;

  public PreferenceOptionsService(
      PreferenceOptionRepository preferenceOptionRepository,
      PreferenceOptionMapper preferenceOptionMapper) {
    this.preferenceOptionRepository = preferenceOptionRepository;
    this.preferenceOptionMapper = preferenceOptionMapper;
  }

  @Transactional(readOnly = true)
  public PreferenceOptionsResponse listActiveOptions() {
    List<PreferenceOptionItemResponse> options =
        preferenceOptionRepository.findByActiveTrueOrderByTypeAscSortOrderAscValueAsc().stream()
            .map(preferenceOptionMapper::toItemResponse)
            .toList();
    return new PreferenceOptionsResponse(options);
  }
}
