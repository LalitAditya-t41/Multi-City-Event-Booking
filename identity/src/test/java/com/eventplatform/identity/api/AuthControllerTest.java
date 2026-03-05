package com.eventplatform.identity.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.identity.api.dto.request.RegisterRequest;
import com.eventplatform.identity.api.dto.response.AccessTokenResponse;
import com.eventplatform.identity.api.dto.response.AuthTokensResponse;
import com.eventplatform.identity.api.dto.response.MessageResponse;
import com.eventplatform.identity.api.dto.response.RegisterResponse;
import com.eventplatform.identity.service.AuthService;
import com.eventplatform.identity.service.PreferenceOptionsService;
import com.eventplatform.identity.service.UserSettingsService;
import com.eventplatform.identity.service.UserWalletService;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.JwtAuthenticationFilter;
import com.eventplatform.shared.security.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = com.eventplatform.identity.IdentityTestApplication.class,
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
    })
@AutoConfigureMockMvc
@Import(SecurityConfig.class)
class AuthControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AuthService authService;
  @MockitoBean private UserSettingsService userSettingsService;
  @MockitoBean private PreferenceOptionsService preferenceOptionsService;
  @MockitoBean private UserWalletService userWalletService;

  @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;

  @BeforeEach
  void setUpFilterPassThrough() throws Exception {
    org.mockito.Mockito.doAnswer(
            invocation -> {
              jakarta.servlet.FilterChain chain = invocation.getArgument(2);
              chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
              return null;
            })
        .when(jwtAuthenticationFilter)
        .doFilter(any(), any(), any());
  }

  @Test
  void should_return_201_on_register_when_payload_valid() throws Exception {
    when(authService.register(any(RegisterRequest.class))).thenReturn(new RegisterResponse(101L));

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType("application/json")
                .content(
                    "{"
                        + "\"email\":\"user@example.com\","
                        + "\"password\":\"strong-pass-123\""
                        + "}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.userId").value(101L));
  }

  @Test
  void should_return_409_on_register_when_email_exists() throws Exception {
    when(authService.register(any(RegisterRequest.class)))
        .thenThrow(
            new com.eventplatform.identity.exception.IdentityException(
                "Email already in use", "DUPLICATE_EMAIL", HttpStatus.CONFLICT));

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType("application/json")
                .content(
                    "{"
                        + "\"email\":\"user@example.com\","
                        + "\"password\":\"strong-pass-123\""
                        + "}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("DUPLICATE_EMAIL"));
  }

  @Test
  void should_return_400_on_register_when_role_field_included_in_request() throws Exception {
    doAnswer(
            invocation -> {
              RegisterRequest request = invocation.getArgument(0);
              request.validateNoUnknownFields();
              return new RegisterResponse(1L);
            })
        .when(authService)
        .register(any(RegisterRequest.class));

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType("application/json")
                .content(
                    "{"
                        + "\"email\":\"user@example.com\","
                        + "\"password\":\"strong-pass-123\","
                        + "\"role\":\"ADMIN\""
                        + "}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("UNKNOWN_FIELDS"));
  }

  @Test
  void should_return_200_and_tokens_on_login_when_credentials_valid() throws Exception {
    when(authService.login(any()))
        .thenReturn(new AuthTokensResponse("access-token", "refresh-token"));

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(
                    "{" + "\"email\":\"user@example.com\"," + "\"password\":\"plain-pass\"" + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("access-token"))
        .andExpect(jsonPath("$.refreshToken").value("refresh-token"));
  }

  @Test
  void should_return_401_on_login_when_credentials_invalid() throws Exception {
    when(authService.login(any()))
        .thenThrow(
            new com.eventplatform.identity.exception.IdentityException(
                "Invalid credentials", "INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED));

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(
                    "{" + "\"email\":\"user@example.com\"," + "\"password\":\"bad-pass\"" + "}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
  }

  @Test
  void should_return_200_on_token_refresh_when_refresh_token_valid() throws Exception {
    when(authService.refreshToken("refresh-ok")).thenReturn(new AccessTokenResponse("new-access"));

    mockMvc
        .perform(
            post("/api/v1/auth/token/refresh")
                .contentType("application/json")
                .content("{\"refreshToken\":\"refresh-ok\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("new-access"));
  }

  @Test
  void should_return_401_on_token_refresh_when_refresh_token_invalid() throws Exception {
    when(authService.refreshToken("refresh-bad"))
        .thenThrow(
            new com.eventplatform.identity.exception.IdentityException(
                "Refresh token invalid", "REFRESH_TOKEN_INVALID", HttpStatus.UNAUTHORIZED));

    mockMvc
        .perform(
            post("/api/v1/auth/token/refresh")
                .contentType("application/json")
                .content("{\"refreshToken\":\"refresh-bad\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("REFRESH_TOKEN_INVALID"));
  }

  @Test
  void should_return_200_on_logout() throws Exception {
    when(authService.logout("refresh-ok"))
        .thenReturn(new MessageResponse("Logged out successfully"));

    mockMvc
        .perform(
            post("/api/v1/auth/logout")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"refreshToken\":\"refresh-ok\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("Logged out successfully"));
  }

  @Test
  void should_return_401_for_protected_endpoint_when_jwt_missing_or_expired() throws Exception {
    mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
  }

  private UsernamePasswordAuthenticationToken userAuthentication() {
    return new UsernamePasswordAuthenticationToken(
        new AuthenticatedUser(1L, "USER", null, null),
        null,
        List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
