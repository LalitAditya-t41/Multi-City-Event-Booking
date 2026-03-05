package com.eventplatform.identity.api.controller;

import com.eventplatform.identity.api.dto.request.LoginRequest;
import com.eventplatform.identity.api.dto.request.LogoutRequest;
import com.eventplatform.identity.api.dto.request.PasswordChangeRequest;
import com.eventplatform.identity.api.dto.request.PasswordResetConfirmRequest;
import com.eventplatform.identity.api.dto.request.PasswordResetRequest;
import com.eventplatform.identity.api.dto.request.RefreshTokenRequest;
import com.eventplatform.identity.api.dto.request.RegisterRequest;
import com.eventplatform.identity.api.dto.response.AccessTokenResponse;
import com.eventplatform.identity.api.dto.response.AuthTokensResponse;
import com.eventplatform.identity.api.dto.response.MessageResponse;
import com.eventplatform.identity.api.dto.response.RegisterResponse;
import com.eventplatform.identity.api.dto.response.UserProfileResponse;
import com.eventplatform.identity.service.AuthService;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.Roles;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
    return authService.register(request);
  }

  @PostMapping("/login")
  public AuthTokensResponse login(@Valid @RequestBody LoginRequest request) {
    return authService.login(request);
  }

  @PostMapping("/token/refresh")
  public AccessTokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
    return authService.refreshToken(request.refreshToken());
  }

  @PostMapping("/logout")
  @PreAuthorize("hasRole('" + Roles.USER + "')")
  public MessageResponse logout(@Valid @RequestBody LogoutRequest request) {
    return authService.logout(request.refreshToken());
  }

  @GetMapping("/me")
  @PreAuthorize("hasRole('" + Roles.USER + "')")
  public UserProfileResponse me(Authentication authentication) {
    return authService.me(currentUserId(authentication));
  }

  @PostMapping("/password/change")
  @PreAuthorize("hasRole('" + Roles.USER + "')")
  public MessageResponse changePassword(
      Authentication authentication, @Valid @RequestBody PasswordChangeRequest request) {
    return authService.changePassword(currentUserId(authentication), request);
  }

  @PostMapping("/password/reset")
  public MessageResponse requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
    return authService.requestPasswordReset(request.email());
  }

  @PostMapping("/password/reset/confirm")
  public MessageResponse confirmPasswordReset(
      @Valid @RequestBody PasswordResetConfirmRequest request) {
    return authService.confirmPasswordReset(request);
  }

  private Long currentUserId(Authentication authentication) {
    return ((AuthenticatedUser) authentication.getPrincipal()).userId();
  }
}
