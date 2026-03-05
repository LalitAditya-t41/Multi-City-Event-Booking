package com.eventplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.shared.common.exception.ValidationException;
import com.eventplatform.shared.eventbrite.domain.OrganizationAuth;
import com.eventplatform.shared.eventbrite.service.EbTokenStore;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrgOAuthServiceTest {

  @Mock private EbTokenStore tokenStore;

  private OrgOAuthService orgOAuthService;

  @BeforeEach
  void setUp() {
    orgOAuthService = new OrgOAuthService(tokenStore, "client-id", "http://callback");
  }

  @Test
  void should_generate_authorization_url_and_store_csrf_state() throws Exception {
    String url = orgOAuthService.buildAuthorizationUrl(1L);
    String state = extractState(url);

    Map<String, ?> stateStore = getStateStore();

    assertThat(stateStore).containsKey(state);
  }

  @Test
  void should_call_exchangeAndStore_and_return_CONNECTED_when_code_and_state_valid()
      throws Exception {
    String url = orgOAuthService.buildAuthorizationUrl(1L);
    String state = extractState(url);
    OrganizationAuth auth = new OrganizationAuth(1L, "eb-org", "token");
    when(tokenStore.connectOrganization(eq(1L), eq("code"), eq("http://callback")))
        .thenReturn(auth);

    OrganizationAuth result = orgOAuthService.handleCallback(1L, "code", state);

    assertThat(result).isEqualTo(auth);
    verify(tokenStore).connectOrganization(1L, "code", "http://callback");
  }

  @Test
  void should_throw_ValidationException_when_csrf_state_invalid_or_expired() {
    assertThatThrownBy(() -> orgOAuthService.handleCallback(1L, "code", "bad-state"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void should_throw_ValidationException_when_csrf_state_expired() throws Exception {
    String url = orgOAuthService.buildAuthorizationUrl(1L);
    String state = extractState(url);
    Map<String, Object> stateStore = getStateStore();
    stateStore.put(state, newStateEntry(1L, Instant.now().minus(Duration.ofMinutes(11))));

    assertThatThrownBy(() -> orgOAuthService.handleCallback(1L, "code", state))
        .isInstanceOf(ValidationException.class);
  }

  private String extractState(String url) {
    String query = URI.create(url).getQuery();
    for (String part : query.split("&")) {
      if (part.startsWith("state=")) {
        return part.substring("state=".length());
      }
    }
    throw new IllegalStateException("state not found");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getStateStore() throws Exception {
    Field field = OrgOAuthService.class.getDeclaredField("stateStore");
    field.setAccessible(true);
    return (Map<String, Object>) field.get(orgOAuthService);
  }

  private Object newStateEntry(Long orgId, Instant createdAt) throws Exception {
    Class<?> entryClass =
        Class.forName("com.eventplatform.admin.service.OrgOAuthService$StateEntry");
    Constructor<?> ctor = entryClass.getDeclaredConstructor(Long.class, Instant.class);
    ctor.setAccessible(true);
    return ctor.newInstance(orgId, createdAt);
  }
}
