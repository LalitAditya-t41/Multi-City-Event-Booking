package com.eventplatform.identity.service;

import com.eventplatform.identity.api.dto.request.LoginRequest;
import com.eventplatform.identity.api.dto.request.PasswordChangeRequest;
import com.eventplatform.identity.api.dto.request.PasswordResetConfirmRequest;
import com.eventplatform.identity.api.dto.request.RegisterRequest;
import com.eventplatform.identity.api.dto.response.AccessTokenResponse;
import com.eventplatform.identity.api.dto.response.AuthTokensResponse;
import com.eventplatform.identity.api.dto.response.MessageResponse;
import com.eventplatform.identity.api.dto.response.RegisterResponse;
import com.eventplatform.identity.api.dto.response.UserProfileResponse;
import com.eventplatform.identity.domain.PasswordResetToken;
import com.eventplatform.identity.domain.RefreshToken;
import com.eventplatform.identity.domain.User;
import com.eventplatform.identity.domain.UserWallet;
import com.eventplatform.identity.exception.IdentityException;
import com.eventplatform.identity.exception.UserNotFoundException;
import com.eventplatform.identity.mapper.UserMapper;
import com.eventplatform.identity.repository.PasswordResetTokenRepository;
import com.eventplatform.identity.repository.RefreshTokenRepository;
import com.eventplatform.identity.repository.UserRepository;
import com.eventplatform.identity.repository.UserWalletRepository;
import com.eventplatform.shared.common.exception.ValidationException;
import com.eventplatform.shared.security.JwtTokenProvider;
import com.eventplatform.shared.security.SecurityConstants;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final UserRepository userRepository;
  private final UserWalletRepository userWalletRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final PasswordResetTokenRepository passwordResetTokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenProvider jwtTokenProvider;
  private final UserMapper userMapper;

  public AuthService(
      UserRepository userRepository,
      UserWalletRepository userWalletRepository,
      RefreshTokenRepository refreshTokenRepository,
      PasswordResetTokenRepository passwordResetTokenRepository,
      PasswordEncoder passwordEncoder,
      JwtTokenProvider jwtTokenProvider,
      UserMapper userMapper) {
    this.userRepository = userRepository;
    this.userWalletRepository = userWalletRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.passwordResetTokenRepository = passwordResetTokenRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtTokenProvider = jwtTokenProvider;
    this.userMapper = userMapper;
  }

  @Transactional
  public RegisterResponse register(RegisterRequest request) {
    request.validateNoUnknownFields();

    String normalizedEmail = normalizeEmail(request.getEmail());
    if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
      throw new IdentityException("Email already in use", "DUPLICATE_EMAIL", HttpStatus.CONFLICT);
    }

    User user = new User(normalizedEmail, passwordEncoder.encode(request.getPassword()));
    User savedUser = userRepository.save(user);
    userWalletRepository.save(new UserWallet(savedUser));
    return userMapper.toRegisterResponse(savedUser);
  }

  @Transactional
  public AuthTokensResponse login(LoginRequest request) {
    User user =
        userRepository
            .findByEmailIgnoreCase(normalizeEmail(request.email()))
            .orElseThrow(
                () ->
                    new IdentityException(
                        "Invalid credentials", "INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED));

    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      throw new IdentityException(
          "Invalid credentials", "INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED);
    }

    String accessToken =
        jwtTokenProvider.generateToken(user.getId(), user.getRole(), null, user.getEmail());
    String refreshTokenRaw = generateOpaqueToken();
    String refreshTokenHash = hashToken(refreshTokenRaw);

    RefreshToken refreshToken =
        new RefreshToken(
            user,
            refreshTokenHash,
            Instant.now().plusSeconds(SecurityConstants.REFRESH_TOKEN_TTL_S));
    refreshTokenRepository.save(refreshToken);

    return new AuthTokensResponse(accessToken, refreshTokenRaw);
  }

  @Transactional
  public AccessTokenResponse refreshToken(String refreshTokenRaw) {
    RefreshToken refreshToken =
        refreshTokenRepository
            .findByTokenHash(hashToken(refreshTokenRaw))
            .orElseThrow(
                () ->
                    new IdentityException(
                        "Refresh token invalid", "REFRESH_TOKEN_INVALID", HttpStatus.UNAUTHORIZED));

    if (refreshToken.isRevoked() || refreshToken.isExpired(Instant.now())) {
      throw new IdentityException(
          "Refresh token invalid", "REFRESH_TOKEN_INVALID", HttpStatus.UNAUTHORIZED);
    }

    String accessToken =
        jwtTokenProvider.generateToken(
            refreshToken.getUser().getId(),
            refreshToken.getUser().getRole(),
            null,
            refreshToken.getUser().getEmail());
    return new AccessTokenResponse(accessToken);
  }

  @Transactional
  public MessageResponse logout(String refreshTokenRaw) {
    Optional<RefreshToken> refreshTokenOpt =
        refreshTokenRepository.findByTokenHash(hashToken(refreshTokenRaw));
    refreshTokenOpt.ifPresent(
        token -> {
          token.revoke(Instant.now());
          refreshTokenRepository.save(token);
        });
    return new MessageResponse("Logged out successfully");
  }

  @Transactional(readOnly = true)
  public UserProfileResponse me(Long userId) {
    User user =
        userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    return userMapper.toProfileResponse(user);
  }

  @Transactional
  public MessageResponse changePassword(Long userId, PasswordChangeRequest request) {
    User user =
        userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

    if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
      throw new ValidationException("Current password incorrect", "CURRENT_PASSWORD_INCORRECT");
    }

    if (!request.newPassword().equals(request.confirmPassword())) {
      throw new ValidationException("Password mismatch", "PASSWORD_MISMATCH");
    }

    user.changePassword(passwordEncoder.encode(request.newPassword()));
    userRepository.save(user);
    return new MessageResponse("Password changed successfully");
  }

  @Transactional
  public MessageResponse requestPasswordReset(String email) {
    Optional<User> userOptional = userRepository.findByEmailIgnoreCase(normalizeEmail(email));
    if (userOptional.isPresent()) {
      User user = userOptional.get();
      passwordResetTokenRepository.invalidateActiveByUserId(user.getId(), Instant.now());
      PasswordResetToken token =
          new PasswordResetToken(
              user,
              hashToken(generateOpaqueToken()),
              Instant.now().plusSeconds(SecurityConstants.RESET_TOKEN_TTL_S));
      passwordResetTokenRepository.save(token);
    }

    return new MessageResponse("Password reset token generated");
  }

  @Transactional
  public MessageResponse confirmPasswordReset(PasswordResetConfirmRequest request) {
    if (!request.newPassword().equals(request.confirmPassword())) {
      throw new ValidationException("Password mismatch", "PASSWORD_MISMATCH");
    }

    PasswordResetToken token =
        passwordResetTokenRepository
            .findByTokenHash(hashToken(request.token()))
            .orElseThrow(
                () -> new ValidationException("Reset token invalid", "RESET_TOKEN_INVALID"));

    Instant now = Instant.now();
    if (token.isConsumed() || token.isExpired(now)) {
      throw new ValidationException("Reset token invalid", "RESET_TOKEN_INVALID");
    }

    PasswordResetToken latestToken =
        passwordResetTokenRepository
            .findTopByUserIdOrderByCreatedAtDesc(token.getUser().getId())
            .orElseThrow(
                () -> new ValidationException("Reset token invalid", "RESET_TOKEN_INVALID"));

    if (!latestToken.getId().equals(token.getId())) {
      throw new ValidationException("Reset token invalid", "RESET_TOKEN_INVALID");
    }

    User user = token.getUser();
    user.changePassword(passwordEncoder.encode(request.newPassword()));
    userRepository.save(user);

    token.consume(now);
    passwordResetTokenRepository.save(token);

    return new MessageResponse("Password reset successfully");
  }

  private String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private String generateOpaqueToken() {
    byte[] bytes = new byte[32];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String hashToken(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] encoded = digest.digest(token.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(encoded.length * 2);
      for (byte b : encoded) {
        builder.append(String.format("%02x", b));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
    }
  }
}
