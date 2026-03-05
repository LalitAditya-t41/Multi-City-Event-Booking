package com.eventplatform.shared.openai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiModerationResponse(List<Result> results) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Result(
      boolean flagged,
      Map<String, Boolean> categories,
      @JsonProperty("category_scores") Map<String, Double> categoryScores) {}
}
