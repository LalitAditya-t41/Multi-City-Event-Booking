package com.eventplatform.shared.eventbrite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Provides a pre-configured {@link RestClient} for all Eventbrite facade implementations.
 * Base URL and Authorization header are applied from {@link EbProperties}.
 */
@Configuration
public class EbRestClientConfig {

    @Bean(name = "ebRestClient")
    public RestClient ebRestClient(EbProperties props, RestClient.Builder builder) {
        return builder
            .baseUrl(props.getBaseUrl())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }
}
