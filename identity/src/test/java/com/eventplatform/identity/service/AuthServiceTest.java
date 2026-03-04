package com.eventplatform.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.identity.api.dto.request.LoginRequest;
import com.eventplatform.identity.api.dto.request.PasswordChangeRequest;
import com.eventplatform.identity.api.dto.request.PasswordResetConfirmRequest;
import com.eventplatform.identity.api.dto.request.RegisterRequest;
import com.eventplatform.identity.api.dto.response.AccessTokenResponse;
import com.eventplatform.identity.api.dto.response.AuthTokensResponse;
import com.eventplatform.identity.domain.PasswordResetToken;
import com.eventplatform.identity.domain.RefreshToken;
import com.eventplatform.identity.domain.User;
import com.eventplatform.identity.domain.UserWallet;
import com.eventplatform.identity.mapper.UserMapper;
import com.eventplatform.identity.repository.PasswordResetTokenRepository;
import com.eventplatform.identity.repository.RefreshTokenRepository;
import com.eventplatform.identity.repository.UserRepository;
import com.eventplatform.identity.repository.UserWalletRepository;
import com.eventplatform.shared.security.JwtTokenProvider;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserWalletRepository userWalletRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private AuthService authService;

    @Test
    void should_hash_password_with_bcrypt_and_save_user_on_register() throws Exception {
        RegisterRequest request = registerRequest("user@example.com", "plain-pass");
        when(userRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("plain-pass")).thenReturn("encoded-pass");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            setId(saved, 7L);
            return saved;
        });

        authService.register(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("encoded-pass");
    }

    @Test
    void should_create_wallet_for_user_on_registration() throws Exception {
        RegisterRequest request = registerRequest("user@example.com", "plain-pass");
        when(userRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("plain-pass")).thenReturn("encoded-pass");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            setId(saved, 9L);
            return saved;
        });

        authService.register(request);

        verify(userWalletRepository).save(any(UserWallet.class));
    }

    @Test
    void should_issue_access_and_refresh_tokens_on_login() throws Exception {
        User user = new User("user@example.com", "encoded-pass");
        setId(user, 11L);
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("plain-pass", "encoded-pass")).thenReturn(true);
        when(jwtTokenProvider.generateToken(11L, user.getRole(), null, user.getEmail())).thenReturn("access-token");

        AuthTokensResponse response = authService.login(new LoginRequest("user@example.com", "plain-pass"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isNotBlank();
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void should_revoke_refresh_token_on_logout() {
        User user = new User("user@example.com", "hash");
        RefreshToken token = new RefreshToken(user, sha("refresh-token"), Instant.now().plusSeconds(100));
        when(refreshTokenRepository.findByTokenHash(sha("refresh-token"))).thenReturn(Optional.of(token));

        authService.logout("refresh-token");

        assertThat(token.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(token);
    }

    @Test
    void should_issue_new_access_token_when_refresh_token_valid() throws Exception {
        User user = new User("user@example.com", "hash");
        setId(user, 20L);
        RefreshToken token = new RefreshToken(user, sha("refresh-token"), Instant.now().plusSeconds(100));
        when(refreshTokenRepository.findByTokenHash(sha("refresh-token"))).thenReturn(Optional.of(token));
        when(jwtTokenProvider.generateToken(20L, user.getRole(), null, user.getEmail())).thenReturn("new-access");

        AccessTokenResponse response = authService.refreshToken("refresh-token");

        assertThat(response.accessToken()).isEqualTo("new-access");
    }

    @Test
    void should_generate_reset_token_and_invalidate_previous_tokens() throws Exception {
        User user = new User("user@example.com", "hash");
        setId(user, 22L);
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));

        authService.requestPasswordReset("user@example.com");

        verify(passwordResetTokenRepository).invalidateActiveByUserId(eq(22L), any(Instant.class));
        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
    }

    @Test
    void should_reset_password_when_token_is_latest_unexpired_unconsumed() throws Exception {
        User user = new User("user@example.com", "old-hash");
        setId(user, 33L);
        PasswordResetToken token = new PasswordResetToken(user, sha("reset-token"), Instant.now().plusSeconds(100));
        setId(token, 300L);

        when(passwordResetTokenRepository.findByTokenHash(sha("reset-token"))).thenReturn(Optional.of(token));
        when(passwordResetTokenRepository.findTopByUserIdOrderByCreatedAtDesc(33L)).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("new-pass")).thenReturn("new-hash");

        authService.confirmPasswordReset(new PasswordResetConfirmRequest("reset-token", "new-pass", "new-pass"));

        verify(userRepository).save(user);
        verify(passwordResetTokenRepository).save(token);
        assertThat(token.isConsumed()).isTrue();
    }

    @Test
    void should_change_password_when_current_password_matches() {
        User user = new User("user@example.com", "old-hash");
        when(userRepository.findById(44L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("old-pass", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-pass")).thenReturn("new-hash");

        authService.changePassword(44L, new PasswordChangeRequest("old-pass", "new-pass", "new-pass"));

        verify(userRepository).save(user);
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
    }

    private RegisterRequest registerRequest(String email, String password) throws Exception {
        RegisterRequest request = new RegisterRequest();
        Field emailField = RegisterRequest.class.getDeclaredField("email");
        emailField.setAccessible(true);
        emailField.set(request, email);
        Field passwordField = RegisterRequest.class.getDeclaredField("password");
        passwordField.setAccessible(true);
        passwordField.set(request, password);
        return request;
    }

    private void setId(Object entity, Long idValue) throws Exception {
        Field id = entity.getClass().getSuperclass().getDeclaredField("id");
        id.setAccessible(true);
        id.set(entity, idValue);
    }

    private String sha(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(encoded.length * 2);
            for (byte b : encoded) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
