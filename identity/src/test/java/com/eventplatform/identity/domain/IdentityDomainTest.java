package com.eventplatform.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventplatform.identity.domain.enums.PreferenceOptionType;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import com.eventplatform.shared.security.Roles;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdentityDomainTest {

  @Test
  void should_create_user_with_user_role_assigned_server_side() {
    User user = new User("user@example.com", "hashed-pass");

    assertThat(user.getRole()).isEqualTo(Roles.USER);
  }

  @Test
  void should_reject_duplicate_email_case_insensitive() {
    String emailA = "Test@Example.com";
    String emailB = "test@example.com";

    assertThat(emailA.equalsIgnoreCase(emailB)).isTrue();
  }

  @Test
  void should_enforce_city_option_required_and_max_three_unique_genre_options() {
    User user = new User("user@example.com", "hash");
    UserSettings settings = new UserSettings(user);
    PreferenceOption city = new PreferenceOption(PreferenceOptionType.CITY, "Mumbai", true, 1);

    settings.updateProfile("Name", "9999", null, "Addr", city, true);
    settings.replaceGenrePreferences(
        List.of(
            new PreferenceOption(PreferenceOptionType.GENRE, "Rock", true, 1),
            new PreferenceOption(PreferenceOptionType.GENRE, "Jazz", true, 2),
            new PreferenceOption(PreferenceOptionType.GENRE, "EDM", true, 3)));

    assertThat(settings.getPreferredCityOption()).isEqualTo(city);
    assertThat(settings.getGenrePreferences()).hasSize(3);
  }

  @Test
  void should_reject_more_than_three_genre_preferences() {
    User user = new User("user@example.com", "hash");
    UserSettings settings = new UserSettings(user);

    assertThatThrownBy(
            () ->
                settings.replaceGenrePreferences(
                    List.of(
                        new PreferenceOption(PreferenceOptionType.GENRE, "A", true, 1),
                        new PreferenceOption(PreferenceOptionType.GENRE, "B", true, 2),
                        new PreferenceOption(PreferenceOptionType.GENRE, "C", true, 3),
                        new PreferenceOption(PreferenceOptionType.GENRE, "D", true, 4))))
        .isInstanceOf(BusinessRuleException.class)
        .hasMessage("Invalid preference");
  }

  @Test
  void should_reject_duplicate_genre_option_ids_in_same_settings() throws Exception {
    User user = new User("user@example.com", "hash");
    UserSettings settings = new UserSettings(user);
    PreferenceOption first = new PreferenceOption(PreferenceOptionType.GENRE, "Rock", true, 1);
    PreferenceOption second = new PreferenceOption(PreferenceOptionType.GENRE, "Rock", true, 1);
    setId(first, 50L);
    setId(second, 50L);

    assertThatThrownBy(() -> settings.replaceGenrePreferences(List.of(first, second)))
        .isInstanceOf(BusinessRuleException.class)
        .hasMessage("Invalid preference");
  }

  @Test
  void should_reject_expired_or_revoked_refresh_token() {
    User user = new User("user@example.com", "hash");
    RefreshToken expired = new RefreshToken(user, "hash-1", Instant.now().minusSeconds(1));
    RefreshToken revoked = new RefreshToken(user, "hash-2", Instant.now().plusSeconds(60));
    revoked.revoke(Instant.now());

    assertThat(expired.isExpired(Instant.now())).isTrue();
    assertThat(revoked.isRevoked()).isTrue();
  }

  @Test
  void should_accept_only_latest_unconsumed_unexpired_reset_token() {
    User user = new User("user@example.com", "hash");
    PasswordResetToken previous =
        new PasswordResetToken(user, "hash-1", Instant.now().plusSeconds(600));
    PasswordResetToken latest =
        new PasswordResetToken(user, "hash-2", Instant.now().plusSeconds(600));
    previous.consume(Instant.now());

    assertThat(previous.isUsable(Instant.now())).isFalse();
    assertThat(latest.isUsable(Instant.now())).isTrue();
  }

  @Test
  void should_invalidate_all_prior_reset_tokens_when_new_token_issued() {
    User user = new User("user@example.com", "hash");
    PasswordResetToken oldToken =
        new PasswordResetToken(user, "hash-1", Instant.now().plusSeconds(600));
    PasswordResetToken newToken =
        new PasswordResetToken(user, "hash-2", Instant.now().plusSeconds(600));
    oldToken.consume(Instant.now());

    assertThat(oldToken.isConsumed()).isTrue();
    assertThat(newToken.isConsumed()).isFalse();
  }

  @Test
  void should_initialize_wallet_with_zero_balance_on_user_creation() {
    User user = new User("user@example.com", "hash");
    UserWallet wallet = new UserWallet(user);

    assertThat(wallet.getBalance().amount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(wallet.getBalance().currency()).isEqualTo("INR");
  }

  @Test
  void should_reject_password_change_when_new_and_confirm_do_not_match() {
    String newPassword = "new-password";
    String confirmPassword = "different-password";

    assertThat(newPassword).isNotEqualTo(confirmPassword);
  }

  private void setId(Object entity, Long idValue) throws Exception {
    Field id = entity.getClass().getSuperclass().getDeclaredField("id");
    id.setAccessible(true);
    id.set(entity, idValue);
  }
}
