package com.eventplatform.identity.service;

import com.eventplatform.identity.domain.User;
import com.eventplatform.identity.exception.UserNotFoundException;
import com.eventplatform.identity.repository.UserRepository;
import com.eventplatform.identity.repository.UserSettingsRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InternalIdentityQueryService {

  private final UserRepository userRepository;
  private final UserSettingsRepository userSettingsRepository;

  public InternalIdentityQueryService(
      UserRepository userRepository, UserSettingsRepository userSettingsRepository) {
    this.userRepository = userRepository;
    this.userSettingsRepository = userSettingsRepository;
  }

  @Transactional(readOnly = true)
  public DisplayNameResponse getDisplayName(Long userId) {
    User user =
        userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    Optional<String> fullName = userSettingsRepository.findFullNameByUserId(userId);
    String displayName =
        fullName
            .filter(name -> !name.isBlank())
            .orElseGet(() -> user.getEmail() == null ? "Anonymous" : user.getEmail().split("@")[0]);
    return new DisplayNameResponse(userId, displayName);
  }

  @Transactional(readOnly = true)
  public EmailResponse getEmail(Long userId) {
    User user =
        userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    return new EmailResponse(userId, user.getEmail());
  }

  public record DisplayNameResponse(Long userId, String displayName) {}

  public record EmailResponse(Long userId, String email) {}
}
