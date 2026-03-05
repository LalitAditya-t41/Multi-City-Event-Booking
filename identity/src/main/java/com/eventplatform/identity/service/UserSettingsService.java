package com.eventplatform.identity.service;

import com.eventplatform.identity.api.dto.request.UserSettingsUpsertRequest;
import com.eventplatform.identity.api.dto.response.UserSettingsResponse;
import com.eventplatform.identity.domain.PreferenceOption;
import com.eventplatform.identity.domain.User;
import com.eventplatform.identity.domain.UserSettings;
import com.eventplatform.identity.domain.enums.PreferenceOptionType;
import com.eventplatform.identity.exception.UserNotFoundException;
import com.eventplatform.identity.mapper.UserSettingsMapper;
import com.eventplatform.identity.repository.PreferenceOptionRepository;
import com.eventplatform.identity.repository.UserRepository;
import com.eventplatform.identity.repository.UserSettingsRepository;
import com.eventplatform.shared.common.exception.ValidationException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserSettingsService {

  private final UserSettingsRepository userSettingsRepository;
  private final UserRepository userRepository;
  private final PreferenceOptionRepository preferenceOptionRepository;
  private final UserSettingsMapper userSettingsMapper;

  public UserSettingsService(
      UserSettingsRepository userSettingsRepository,
      UserRepository userRepository,
      PreferenceOptionRepository preferenceOptionRepository,
      UserSettingsMapper userSettingsMapper) {
    this.userSettingsRepository = userSettingsRepository;
    this.userRepository = userRepository;
    this.preferenceOptionRepository = preferenceOptionRepository;
    this.userSettingsMapper = userSettingsMapper;
  }

  @Transactional(readOnly = true)
  public UserSettingsResponse getMySettings(Long userId) {
    UserSettings settings =
        userSettingsRepository
            .findWithPreferencesByUserId(userId)
            .orElseGet(() -> new UserSettings(getUserOrThrow(userId)));
    return userSettingsMapper.toResponse(settings);
  }

  @Transactional
  public UserSettingsResponse upsertMySettings(Long userId, UserSettingsUpsertRequest request) {
    validateGenres(request.preferredGenreOptionIds());

    User user = getUserOrThrow(userId);
    UserSettings settings =
        userSettingsRepository
            .findWithPreferencesByUserId(userId)
            .orElseGet(() -> new UserSettings(user));

    PreferenceOption cityOption =
        preferenceOptionRepository
            .findByIdAndTypeAndActiveTrue(
                request.preferredCityOptionId(), PreferenceOptionType.CITY)
            .orElseThrow(() -> new ValidationException("Invalid preference", "INVALID_PREFERENCE"));

    Set<Long> genreIds = new LinkedHashSet<>(request.preferredGenreOptionIds());
    List<PreferenceOption> genreOptions =
        preferenceOptionRepository.findByIdInAndTypeAndActiveTrue(
            genreIds, PreferenceOptionType.GENRE);

    if (genreOptions.size() != genreIds.size()) {
      throw new ValidationException("Invalid preference", "INVALID_PREFERENCE");
    }

    settings.updateProfile(
        request.fullName(),
        request.phone(),
        request.dob(),
        request.address(),
        cityOption,
        request.notificationOptIn());
    settings.replaceGenrePreferences(genreOptions);

    UserSettings saved = userSettingsRepository.save(settings);
    return userSettingsMapper.toResponse(saved);
  }

  private void validateGenres(List<Long> genreOptionIds) {
    if (genreOptionIds == null) {
      throw new ValidationException("Invalid preference", "INVALID_PREFERENCE");
    }
    if (genreOptionIds.size() > 3) {
      throw new ValidationException("Invalid preference", "INVALID_PREFERENCE");
    }

    Set<Long> uniqueIds = new LinkedHashSet<>(genreOptionIds);
    if (uniqueIds.size() != genreOptionIds.size()) {
      throw new ValidationException("Invalid preference", "INVALID_PREFERENCE");
    }
  }

  private User getUserOrThrow(Long userId) {
    return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
  }
}
