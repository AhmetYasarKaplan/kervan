package io.github.ahmetyasarkaplan.kervan.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.Ranker;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.core.BatchScoringRanker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    classes = KervanHttpIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KervanHttpIntegrationTest {

  @LocalServerPort private int port;

  @Test
  void shouldExposeOnlyBoundPipelineWithSanitizedResponses() throws Exception {
    HttpResponse<String> success = post("sample", "{\"term\":\"camera\"}");
    assertThat(success.statusCode()).isEqualTo(200);
    assertThat(success.body())
        .contains("\"pipelineName\":\"sample\"")
        .contains("\"id\":\"camera\"")
        .contains("\"retrieverName\":\"search\"")
        .contains("\"retrieverName\":\"recommendations\"");

    HttpResponse<String> partial = post("sample", "{\"term\":\"partial\"}");
    assertThat(partial.statusCode()).isEqualTo(200);
    assertThat(partial.body())
        .contains("\"status\":\"FAILED\"")
        .contains("\"id\":\"camera\"")
        .doesNotContain("provider exploded");

    HttpResponse<String> fatal = post("sample", "{\"term\":\"fatal\"}");
    assertThat(fatal.statusCode()).isEqualTo(503);
    assertThat(fatal.body())
        .contains("pipeline_failed", "REQUIRED_RETRIEVER_FAILED")
        .doesNotContain("provider exploded", "fatal");

    HttpResponse<String> unknown = post("other", "{\"term\":\"camera\"}");
    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(unknown.body()).contains("unknown_pipeline").doesNotContain("camera");

    HttpResponse<String> invalid = post("sample", "{\"term\":");
    assertThat(invalid.statusCode()).isEqualTo(400);
    assertThat(invalid.body()).contains("invalid_request").doesNotContain("Exception");
  }

  @Test
  void serializesSeparateScoresSourceHintsAndExplicitRankingFallback() throws Exception {
    var mapper = JsonMapper.builder().build();
    var response = post("sample", "{\"term\":\"camera\"}");
    assertThat(response.statusCode()).isEqualTo(200);
    var json = mapper.readTree(response.body());
    assertThat(json.path("rankingOutcome").path("status").asString()).isEqualTo("SUCCESS");
    var candidate = json.path("candidates").get(0);
    assertThat(candidate.path("rerankingScore").path("rerankScore").asDouble()).isEqualTo(0.9);
    assertThat(candidate.path("rerankingScore").path("fusionScore").asDouble()).isPositive();
    assertThat(candidate.path("contributions").get(0).path("attributes").path("origin").asString())
        .isEqualTo("catalog");
    var fallback = post("sample", "{\"term\":\"ranking-fails\"}");
    assertThat(fallback.statusCode()).isEqualTo(200);
    var degraded = mapper.readTree(fallback.body());
    assertThat(degraded.path("rankingOutcome").path("status").asString()).isEqualTo("FAILED");
    assertThat(degraded.path("candidates").get(0).path("rerankingScore").isNull()).isTrue();
    assertThat(fallback.body()).doesNotContain("private model response");
  }

  private HttpResponse<String> post(String name, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/kervan/pipelines/" + name + "/execute"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  public record TestQuery(String term) {}

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApplication {

    @Bean
    CandidateRetriever<TestQuery, String> searchRetriever() {
      return (query, context) -> {
        if ("fatal".equals(query.term())) {
          throw new IllegalStateException("provider exploded");
        }
        return List.of(
            new SourceCandidate<>("camera", 2.0, Map.of("origin", "catalog")),
            SourceCandidate.of("lens", 1.0));
      };
    }

    @Bean
    Ranker<TestQuery, String> batchRanker() {
      return new BatchScoringRanker<>(
          (query, candidates, context) -> {
            if (query.term().equals("ranking-fails")) {
              throw new IllegalStateException("private model response");
            }
            return candidates.stream()
                .collect(
                    Collectors.toMap(
                        candidate -> candidate.id(),
                        candidate -> candidate.id().equals("camera") ? 0.9 : 0.2));
          });
    }

    @Bean
    CandidateRetriever<TestQuery, String> recommendationRetriever() {
      return (query, context) -> {
        if ("partial".equals(query.term())) {
          throw new IllegalStateException("provider exploded");
        }
        return List.of(SourceCandidate.of("camera", 5.0));
      };
    }

    @Bean
    HttpPipelineBinding<TestQuery, String> demoBinding() {
      return new HttpPipelineBinding<>("sample", TestQuery.class, String.class);
    }
  }
}
