package com.eventplatform.identity.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.identity.api.dto.request.UserSettingsUpsertRequest;
import com.eventplatform.identity.domain.PreferenceOption;
import com.eventplatform.identity.domain.User;
import com.eventplatform.identity.domain.enums.PreferenceOptionType;
import com.eventplatform.identity.mapper.UserSettingsMapper;
import com.eventplatform.identity.repository.PreferenceOptionRepository;
import com.eventplatform.identity.repository.UserRepository;
import com.eventplatform.identity.repository.UserSettingsRepository;
import com.eventplatform.shared.common.exception.ValidationException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserSettingsServiceTest {

  @Mock private UserSettingsRepository userSettingsRepository;
  @Mock private UserRepository userRepository;
  @Mock private PreferenceOptionRepository preferenceOptionRepository;
  @Mock private UserSettingsMapper userSettingsMapper;

  @InjectMocks private UserSettingsService userSettingsService;

  @Test
  void should_validate_active_preference_options_before_upsert() {
    User user = new User("user@example.com", "hash");
    PreferenceOption city = new PreferenceOption(PreferenceOptionType.CITY, "Pune", true, 1);

    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    when(preferenceOptionRepository.findByIdAndTypeAndActiveTrue(10L, PreferenceOptionType.CITY))
        .thenReturn(Optional.of(city));
    when(preferenceOptionRepository.findByIdInAndTypeAndActiveTrue(
            anyCollection(), org.mockito.ArgumentMatchers.eq(PreferenceOptionType.GENRE)))
        .thenReturn(List.of(new PreferenceOption(PreferenceOptionType.GENRE, "Rock", true, 1)));

    UserSettingsUpsertRequest request =
        new UserSettingsUpsertRequest(
            "Full Name", "9999999999", null, "Address", 10L, List.of(20L, 21L), true);

    assertThatThrownBy(() -> userSettingsService.upsertMySettings(1L, request))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Invalid preference");

    verify(userSettingsRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }
}
