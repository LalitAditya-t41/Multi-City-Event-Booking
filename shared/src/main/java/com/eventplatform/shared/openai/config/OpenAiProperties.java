package com.eventplatform.shared.openai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {

  private String baseUrl = "https://api.openai.com";
  private String apiKey = "";
  private String moderationModel = "omni-moderation-latest";

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

  public String getModerationModel() {
    return moderationModel;
  }

  public void setModerationModel(String moderationModel) {
    this.moderationModel = moderationModel;
  }
}
