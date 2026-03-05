package com.eventplatform.shared.openai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(OpenAiProperties.class)
public class OpenAiConfig {

  @Bean(name = "openAiRestClient")
  public RestClient openAiRestClient(OpenAiProperties properties, RestClient.Builder builder) {
    return builder
        .baseUrl(properties.getBaseUrl())
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .build();
  }
}
