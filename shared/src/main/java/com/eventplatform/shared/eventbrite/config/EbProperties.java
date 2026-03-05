package com.eventplatform.shared.eventbrite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code eventbrite.*} block from application.yaml.
 *
 * <pre>
 * eventbrite:
 *   base-url: http://localhost:8888
 *   api-key:  ${EVENTBRITE_API_KEY:mock-token}
 *   oauth:
 *     client-id: ...
 *     redirect-uri: ...
 *     encryption-key: ...
 *   retry:
 *     max-attempts: 3
 *     initial-backoff-ms: 250
 *     max-backoff-ms: 5000
 *   webhook:
 *     secret: ...
 *     endpoint-url: ...
 * </pre>
 */
@ConfigurationProperties(prefix = "eventbrite")
public class EbProperties {

  /** Base URL of the Eventbrite API (or mock server for local dev). */
  private String baseUrl;

  /**
   * Static API key used as fallback when OAuth per-org tokens are not yet stored. In production
   * this should be injected via the EVENTBRITE_API_KEY environment variable. For local development
   * against the mock server the default value {@code mock-token} is used.
   */
  private String apiKey;

  private final OAuth oauth = new OAuth();
  private final Retry retry = new Retry();
  private final Webhook webhook = new Webhook();

  // ------------------------------------------------------------------ getters / setters

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public OAuth getOauth() {
    return oauth;
  }

  public Retry getRetry() {
    return retry;
  }

  public Webhook getWebhook() {
    return webhook;
  }

  // ------------------------------------------------------------------ nested records

  public static class OAuth {
    private String clientId;
    private String redirectUri;
    private String encryptionKey;

    public String getClientId() {
      return clientId;
    }

    public void setClientId(String clientId) {
      this.clientId = clientId;
    }

    public String getRedirectUri() {
      return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
      this.redirectUri = redirectUri;
    }

    public String getEncryptionKey() {
      return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
      this.encryptionKey = encryptionKey;
    }
  }

  public static class Retry {
    private int maxAttempts = 3;
    private long initialBackoffMs = 250;
    private long maxBackoffMs = 5000;

    public int getMaxAttempts() {
      return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
    }

    public long getInitialBackoffMs() {
      return initialBackoffMs;
    }

    public void setInitialBackoffMs(long initialBackoffMs) {
      this.initialBackoffMs = initialBackoffMs;
    }

    public long getMaxBackoffMs() {
      return maxBackoffMs;
    }

    public void setMaxBackoffMs(long maxBackoffMs) {
      this.maxBackoffMs = maxBackoffMs;
    }
  }

  public static class Webhook {
    private String secret;
    private String endpointUrl;

    public String getSecret() {
      return secret;
    }

    public void setSecret(String secret) {
      this.secret = secret;
    }

    public String getEndpointUrl() {
      return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
      this.endpointUrl = endpointUrl;
    }
  }
}
