package io.github.ahmetyasarkaplan.kervan.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

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
        return List.of(SourceCandidate.of("camera", 2.0), SourceCandidate.of("lens", 1.0));
      };
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
