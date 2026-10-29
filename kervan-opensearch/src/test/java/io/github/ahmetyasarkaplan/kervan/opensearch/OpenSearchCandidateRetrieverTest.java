package io.github.ahmetyasarkaplan.kervan.opensearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.ScoreDirection;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import jakarta.json.JsonException;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.TransportException;

class OpenSearchCandidateRetrieverTest {
  @Test
  void shouldApplyNativeLimitsOrientScoresMapAttributesAndPreserveOrder() {
    AtomicReference<SearchRequest> request = new AtomicReference<>();
    var response = response(false, List.of(hit("a", 0.25), hit("b", 0.5), hit("c", 0.75)));
    var retriever =
        OpenSearchCandidateRetriever.<String, String, Void>builder(
                client(CompletableFuture.completedFuture(response), request), Void.class)
            .requestFactory(
                (query, context) ->
                    new SearchRequest.Builder()
                        .index("products")
                        .size(999)
                        .timeout("1h")
                        .query(q -> q.matchAll(m -> m)))
            .idMapper(hit -> "catalog:" + hit.id())
            .attributesMapper(hit -> Map.of("index", hit.index()))
            .scoreDirection(ScoreDirection.LOWER_IS_BETTER)
            .build();
    var results = retriever.retrieve("secret", RetrievalContext.of(2, Duration.ofSeconds(2)));
    assertThat(results).extracting(SourceCandidate::id).containsExactly("catalog:a", "catalog:b");
    assertThat(results).extracting(SourceCandidate::score).containsExactly(-0.25, -0.5);
    assertThat(results.getFirst().attributes()).containsEntry("index", "products");
    assertThat(request.get().size()).isEqualTo(2);
    assertThat(request.get().timeout()).endsWith("ms");
    assertThat(Long.parseLong(request.get().timeout().replace("ms", ""))).isBetween(1L, 2000L);
    assertThat(request.get().allowPartialSearchResults()).isFalse();
  }

  @Test
  void shouldCancelTimedOutTransportAndRestoreInterruptFlag() {
    CompletableFuture<SearchResponse<Void>> pending = new CompletableFuture<>();
    var retriever = retriever(client(pending, new AtomicReference<>()));
    assertThatThrownBy(
            () -> retriever.retrieve("secret", RetrievalContext.of(1, Duration.ofMillis(30))))
        .hasMessage("OpenSearch retrieval failed: TIMED_OUT")
        .hasNoCause();
    assertThat(pending.isCancelled()).isTrue();
    CompletableFuture<SearchResponse<Void>> interrupted = new CompletableFuture<>();
    var interruptedRetriever = retriever(client(interrupted, new AtomicReference<>()));
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(
              () ->
                  interruptedRetriever.retrieve(
                      "secret", RetrievalContext.of(1, Duration.ofSeconds(1))))
          .hasMessage("OpenSearch retrieval failed: INTERRUPTED")
          .hasNoCause();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(interrupted.isCancelled()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void shouldRejectSuccessWhenFinalMappingExhaustsBudget() {
    var retriever =
        OpenSearchCandidateRetriever.<String, String, Void>builder(
                client(
                    CompletableFuture.completedFuture(response(false, List.of(hit("a", 1.0)))),
                    new AtomicReference<>()),
                Void.class)
            .requestFactory((q, context) -> new SearchRequest.Builder().index("products"))
            .idMapper(Hit::id)
            .attributesMapper(
                hit -> {
                  try {
                    Thread.sleep(Duration.ofMillis(150));
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                  }
                  return Map.of();
                })
            .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
            .build();
    assertThatThrownBy(
            () -> retriever.retrieve("query", RetrievalContext.of(1, Duration.ofMillis(100))))
        .hasMessage("OpenSearch retrieval failed: TIMED_OUT")
        .hasNoCause();
  }

  @Test
  void shouldSanitizeTransportTimeoutMalformedScoresAndMappingFailures() {
    assertFailure(
        CompletableFuture.failedFuture(new IOException("password=secret")), "TRANSPORT_FAILURE");
    assertFailure(
        CompletableFuture.failedFuture(
            new TransportException(
                "private response", new JsonException("private malformed document"))),
        "INVALID_RESPONSE");
    assertFailure(CompletableFuture.completedFuture(response(true, List.of())), "TIMED_OUT");
    assertFailure(
        CompletableFuture.completedFuture(response(false, List.of(hit("a", Double.NaN)))),
        "INVALID_RESPONSE");
    assertFailure(
        CompletableFuture.completedFuture(response(false, List.of(hit("a", null)))),
        "INVALID_RESPONSE");
    var retriever =
        OpenSearchCandidateRetriever.<String, String, Void>builder(
                client(
                    CompletableFuture.completedFuture(response(false, List.of(hit("a", 1.0)))),
                    new AtomicReference<>()),
                Void.class)
            .requestFactory((q, context) -> new SearchRequest.Builder().index("products"))
            .idMapper(
                hit -> {
                  throw new IllegalArgumentException("private document");
                })
            .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
            .build();
    assertThatThrownBy(
            () -> retriever.retrieve("secret", RetrievalContext.of(1, Duration.ofSeconds(1))))
        .hasMessage("OpenSearch retrieval failed: INVALID_RESPONSE")
        .hasNoCause();
  }

  @Test
  void shouldRequireExplicitScoreDirectionAndSanitizeRequestFactory() {
    var client = client(new CompletableFuture<>(), new AtomicReference<>());
    assertThatThrownBy(
            () ->
                OpenSearchCandidateRetriever.<String, String, Void>builder(client, Void.class)
                    .requestFactory((q, c) -> new SearchRequest.Builder())
                    .idMapper(Hit::id)
                    .build())
        .isInstanceOf(IllegalStateException.class);
    var retriever =
        OpenSearchCandidateRetriever.<String, String, Void>builder(client, Void.class)
            .requestFactory(
                (q, c) -> {
                  throw new IllegalArgumentException("secret query");
                })
            .idMapper(Hit::id)
            .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
            .build();
    assertThatThrownBy(
            () -> retriever.retrieve("secret", RetrievalContext.of(1, Duration.ofSeconds(1))))
        .hasMessage("OpenSearch retrieval failed: INVALID_REQUEST")
        .hasNoCause();
  }

  private static void assertFailure(
      CompletableFuture<SearchResponse<Void>> response, String reason) {
    var retriever = retriever(client(response, new AtomicReference<>()));
    assertThatThrownBy(
            () -> retriever.retrieve("secret", RetrievalContext.of(1, Duration.ofSeconds(1))))
        .hasMessage("OpenSearch retrieval failed: " + reason)
        .hasNoCause();
  }

  private static OpenSearchCandidateRetriever<String, String, Void> retriever(
      OpenSearchClient client) {
    return OpenSearchCandidateRetriever.<String, String, Void>builder(client, Void.class)
        .requestFactory((query, context) -> new SearchRequest.Builder().index("products"))
        .idMapper(Hit::id)
        .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
        .build();
  }

  private static Hit<Void> hit(String id, Double score) {
    return new Hit.Builder<Void>().index("products").id(id).score(score).build();
  }

  private static SearchResponse<Void> response(boolean timedOut, List<Hit<Void>> hits) {
    return new SearchResponse.Builder<Void>()
        .took(1)
        .timedOut(timedOut)
        .shards(s -> s.total(1).successful(1).failed(0))
        .hits(h -> h.hits(hits))
        .build();
  }

  private static OpenSearchClient client(
      CompletableFuture<SearchResponse<Void>> future, AtomicReference<SearchRequest> request) {
    OpenSearchTransport transport =
        (OpenSearchTransport)
            Proxy.newProxyInstance(
                OpenSearchTransport.class.getClassLoader(),
                new Class<?>[] {OpenSearchTransport.class},
                (object, method, args) ->
                    switch (method.getName()) {
                      case "performRequestAsync" -> {
                        request.set((SearchRequest) args[0]);
                        yield future;
                      }
                      case "jsonpMapper" -> new JacksonJsonpMapper();
                      case "close" ->
                          throw new AssertionError("adapter must not close caller transport");
                      default -> null;
                    });
    return new OpenSearchClient(transport);
  }
}
