package com.eventplatform.shared.eventbrite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.eventplatform.shared.eventbrite.domain.OrgAuthStatus;
import com.eventplatform.shared.eventbrite.domain.OrganizationAuth;
import com.eventplatform.shared.eventbrite.dto.response.EbOAuthTokenResponse;
import com.eventplatform.shared.eventbrite.exception.EbAuthException;
import com.eventplatform.shared.eventbrite.repository.OrganizationAuthRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EbTokenStoreTest {

  @Mock private OrganizationAuthRepository repository;
  @Mock private EbOAuthClient oauthClient;
  @Mock private TokenCipher tokenCipher;

  @InjectMocks private EbTokenStore tokenStore;

  @Test
  void should_return_decrypted_access_token_when_token_valid() {
    OrganizationAuth auth = new OrganizationAuth(1L, "eb-org", "enc");
    auth.markConnected("enc", "ref", Instant.now().plusSeconds(3600));
    when(repository.findByOrganizationId(1L)).thenReturn(Optional.of(auth));
    when(tokenCipher.decrypt("enc")).thenReturn("access");

    String token = tokenStore.getAccessToken(1L);

    assertThat(token).isEqualTo("access");
  }

  @Test
  void should_update_token_and_return_new_access_token_after_successful_refresh() {
    OrganizationAuth auth = new OrganizationAuth(1L, "eb-org", "enc");
    auth.markConnected("enc", "refEnc", Instant.now().plusSeconds(10));
    when(repository.findByOrganizationId(1L)).thenReturn(Optional.of(auth));
    when(tokenCipher.decrypt("refEnc")).thenReturn("refresh");
    when(oauthClient.refreshToken("refresh"))
        .thenReturn(
            new EbOAuthTokenResponse(
                "newAccess", "newRefresh", Instant.now().plusSeconds(3600), "eb-org"));
    when(tokenCipher.encrypt("newAccess")).thenReturn("newEnc");
    when(tokenCipher.encrypt("newRefresh")).thenReturn("newRefreshEnc");
    when(tokenCipher.decrypt("newEnc")).thenReturn("newAccess");

    String token = tokenStore.getAccessToken(1L);

    assertThat(token).isEqualTo("newAccess");
    assertThat(auth.getStatus()).isEqualTo(OrgAuthStatus.CONNECTED);
  }

  @Test
  void should_set_status_TOKEN_EXPIRED_and_throw_EbAuthException_when_refresh_fails() {
    OrganizationAuth auth = new OrganizationAuth(1L, "eb-org", "enc");
    auth.markConnected("enc", "refEnc", Instant.now().plusSeconds(10));
    when(repository.findByOrganizationId(1L)).thenReturn(Optional.of(auth));
    when(tokenCipher.decrypt("refEnc")).thenReturn("refresh");
    when(oauthClient.refreshToken("refresh")).thenThrow(new RuntimeException("boom"));

    assertThatThrownBy(() -> tokenStore.getAccessToken(1L)).isInstanceOf(EbAuthException.class);
    assertThat(auth.getStatus()).isEqualTo(OrgAuthStatus.TOKEN_EXPIRED);
  }

  @Test
  void should_throw_EbAuthException_immediately_when_status_is_REVOKED() {
    OrganizationAuth auth = new OrganizationAuth(1L, "eb-org", "enc");
    auth.markRevoked();
    when(repository.findByOrganizationId(1L)).thenReturn(Optional.of(auth));

    assertThatThrownBy(() -> tokenStore.getAccessToken(1L)).isInstanceOf(EbAuthException.class);
  }
}
