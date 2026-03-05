package com.eventplatform.identity.mapper;

import com.eventplatform.identity.api.dto.response.PreferenceSelectionResponse;
import com.eventplatform.identity.api.dto.response.UserSettingsResponse;
import com.eventplatform.identity.domain.PreferenceOption;
import com.eventplatform.identity.domain.UserGenrePreference;
import com.eventplatform.identity.domain.UserSettings;
import java.util.Comparator;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserSettingsMapper {

  default UserSettingsResponse toResponse(UserSettings settings) {
    PreferenceSelectionResponse city = toSelection(settings.getPreferredCityOption());
    List<PreferenceSelectionResponse> genres =
        settings.getGenrePreferences().stream()
            .map(UserGenrePreference::getPreferenceOption)
            .sorted(Comparator.comparingLong(PreferenceOption::getId))
            .map(this::toSelection)
            .toList();

    return new UserSettingsResponse(
        settings.getFullName(),
        settings.getPhone(),
        settings.getDob(),
        settings.getAddress(),
        city,
        genres,
        settings.isNotificationOptIn());
  }

  default PreferenceSelectionResponse toSelection(PreferenceOption option) {
    if (option == null) {
      return null;
    }
    return new PreferenceSelectionResponse(option.getId(), option.getValue());
  }
}
