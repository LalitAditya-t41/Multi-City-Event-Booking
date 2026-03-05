package com.eventplatform.shared.openai.service;

import com.eventplatform.shared.openai.config.OpenAiProperties;
import com.eventplatform.shared.openai.dto.OpenAiModerationResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class OpenAiModerationService {

  private final RestClient restClient;
  private final OpenAiProperties properties;

  public OpenAiModerationService(
      @Qualifier("openAiRestClient") RestClient restClient, OpenAiProperties properties) {
    this.restClient = restClient;
    this.properties = properties;
  }

  public ModerationResult moderate(String inputText) {
    OpenAiModerationResponse response =
        restClient
            .post()
            .uri("/v1/moderations")
            .body(Map.of("model", properties.getModerationModel(), "input", inputText))
            .retrieve()
            .onStatus(
                HttpStatusCode::isError,
                (req, res) -> {
                  throw new IllegalStateException(
                      "OpenAI moderation failed with status " + res.getStatusCode());
                })
            .body(OpenAiModerationResponse.class);

    if (response == null || response.results() == null || response.results().isEmpty()) {
      throw new IllegalStateException("OpenAI moderation returned no results");
    }

    OpenAiModerationResponse.Result result = response.results().get(0);
    Map<String, Double> scores =
        result.categoryScores() == null ? Map.of() : result.categoryScores();
    List<String> flagged = new ArrayList<>();

    if (result.categories() != null) {
      result
          .categories()
          .forEach(
              (category, isFlagged) -> {
                if (Boolean.TRUE.equals(isFlagged)) {
                  flagged.add(category);
                }
              });
    }

    return new ModerationResult(result.flagged(), flagged, new LinkedHashMap<>(scores));
  }

  public record ModerationResult(
      boolean flagged, List<String> flaggedCategories, Map<String, Double> categoryScores) {}
}
