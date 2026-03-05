package com.eventplatform.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

class JwtTokenProviderTest {

  private JwtTokenProvider jwtTokenProvider;

  @BeforeEach
  void setUp() throws Exception {
    // Generate a fresh RSA key pair for tests — no file I/O needed
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
    kpg.initialize(2048);
    KeyPair keyPair = kpg.generateKeyPair();

    // Use no-op paths; we'll inject keys directly via reflection
    ResourceLoader rl = new DefaultResourceLoader();
    jwtTokenProvider =
        new JwtTokenProvider(rl, "classpath:irrelevant.pem", "classpath:irrelevant.pem");

    // Bypass @PostConstruct by injecting the keys directly
    ReflectionTestUtils.setField(jwtTokenProvider, "privateKey", keyPair.getPrivate());
    ReflectionTestUtils.setField(jwtTokenProvider, "publicKey", keyPair.getPublic());
  }

  @Test
  void should_generate_token_with_userId_and_role_without_orgId() {
    String token = jwtTokenProvider.generateToken(42L, "USER");

    assertThat(token).isNotBlank();
    assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    assertThat(jwtTokenProvider.extractUserId(token)).isEqualTo(42L);
    assertThat(jwtTokenProvider.extractRole(token)).isEqualTo("USER");
    assertThat(jwtTokenProvider.extractOrgId(token)).isNull();
  }

  @Test
  void should_generate_token_with_orgId_claim_included() {
    String token = jwtTokenProvider.generateToken(10L, "ADMIN", 99L);

    assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    assertThat(jwtTokenProvider.extractUserId(token)).isEqualTo(10L);
    assertThat(jwtTokenProvider.extractRole(token)).isEqualTo("ADMIN");
    assertThat(jwtTokenProvider.extractOrgId(token)).isEqualTo(99L);
  }

  @Test
  void should_return_null_orgId_when_token_has_no_orgId_claim() {
    // generateToken(userId, role) delegates to generateToken(userId, role, null)
    String token = jwtTokenProvider.generateToken(7L, "USER");

    assertThat(jwtTokenProvider.extractOrgId(token)).isNull();
  }

  @Test
  void should_return_false_for_invalid_token() {
    assertThat(jwtTokenProvider.validateToken("not.a.valid.token")).isFalse();
  }

  @Test
  void should_include_email_claim_when_generateToken_with_email_called() {
    String token = jwtTokenProvider.generateToken(5L, "USER", null, "alice@example.com");

    assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    assertThat(jwtTokenProvider.extractUserId(token)).isEqualTo(5L);
    assertThat(jwtTokenProvider.extractEmail(token)).isEqualTo("alice@example.com");
    assertThat(jwtTokenProvider.extractOrgId(token)).isNull();
  }

  @Test
  void should_return_null_email_when_token_has_no_email_claim() {
    String token = jwtTokenProvider.generateToken(3L, "USER");

    assertThat(jwtTokenProvider.extractEmail(token)).isNull();
  }
}
