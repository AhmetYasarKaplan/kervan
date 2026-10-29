package io.github.ahmetyasarkaplan.kervan.reranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CohereBatchScorerTest {
  private static final String TOKEN = "private-test-token-123";
  private static final String VALID =
      "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}]}";
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final List<RankedCandidate<String>> candidates =
      List.of(candidate("alpha", 0.8), candidate("beta", 0.4));
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private HttpServer server;
  private HttpClient client;
  private java.util.concurrent.ExecutorService serverExecutor;
  private String response = VALID;
  private int status = 200;
  private long headerDelayMillis;
  private long bodyDelayMillis;
  private CountDownLatch requestArrived;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(serverExecutor);
    server.createContext("/v2/rerank", this::handle);
    server.start();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
    serverExecutor.shutdownNow();
    client.close();
  }

  @Test
  void sendsOneBulkRequestAndMapsUnorderedResultsToCanonicalIds() throws Exception {
    AtomicInteger hydrated = new AtomicInteger();
    AtomicInteger credentials = new AtomicInteger();
    RankingContext context = context();
    CohereBatchScorer<String, String> scorer =
        builder()
            .bearerToken(
                () -> {
                  credentials.incrementAndGet();
                  return TOKEN;
                })
            .documentMapper(
                (query, window, suppliedContext) -> {
                  hydrated.incrementAndGet();
                  assertThat(query).isEqualTo("query");
                  assertThat(suppliedContext).isSameAs(context);
                  assertThat(window).containsExactlyElementsOf(candidates);
                  assertThatThrownBy(() -> window.add(candidate("extra", 1)))
                      .isInstanceOf(UnsupportedOperationException.class);
                  return Map.of("beta", "second text", "alpha", "first text");
                })
            .build();

    assertThat(scorer.score("query", candidates, context))
        .containsExactlyInAnyOrderEntriesOf(Map.of("alpha", 0.1, "beta", 0.9));
    assertThat(hydrated).hasValue(1);
    assertThat(credentials).hasValue(1);
    assertThat(calls).hasValue(1);
    assertThat(authorization).hasValue("Bearer " + TOKEN);
    JsonNode json = mapper.readTree(requestBody.get());
    assertThat(json.get("model").asString()).isEqualTo("explicit-model");
    assertThat(json.get("query").asString()).isEqualTo("query");
    assertThat(json.get("documents").get(0).asString()).isEqualTo("first text");
    assertThat(json.get("documents").get(1).asString()).isEqualTo("second text");
    assertThat(json.has("top_n")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "null",
        "{\"results\":null}",
        "{\"results\":[{\"index\":0,\"relevance_score\":1}]}",
        "{\"results\":[{\"index\":0,\"relevance_score\":1},{\"index\":0,\"relevance_score\":2}]}",
        "{\"results\":[{\"index\":-1,\"relevance_score\":1},{\"index\":1,\"relevance_score\":2}]}",
        "{\"results\":[{\"index\":2,\"relevance_score\":1},{\"index\":1,\"relevance_score\":2}]}",
        "{\"results\":[{\"index\":0.5,\"relevance_score\":1},{\"index\":1,\"relevance_score\":2}]}",
        "{\"results\":[{\"index\":2147483648,\"relevance_score\":1},{\"index\":1,\"relevance_score\":2}]}",
        "{\"results\":[{\"index\":0,\"relevance_score\":\"0.1\"},{\"index\":1,\"relevance_score\":2}]}",
        "{\"results\":[{\"index\":0,\"relevance_score\":1e1000},{\"index\":1,\"relevance_score\":2}]}",
        "{\"results\":[{\"index\":0},{\"index\":1,\"relevance_score\":2}]}",
        "{\"results\":[null,{\"index\":1,\"relevance_score\":2}]}",
        "malformed private-test-token-123"
      })
  void rejectsMalformedPartialDuplicateAndNonfiniteResponses(String value) {
    response = value;
    assertThatThrownBy(() -> builder().build().score("query", candidates, context()))
        .isInstanceOf(CohereScoringException.class)
        .hasCause(null)
        .hasMessageNotContaining(TOKEN);
  }

  @Test
  void rejectsTrailingJsonRoots() {
    response = VALID + " {}";
    assertThatThrownBy(() -> builder().build().score("query", candidates, context()))
        .isInstanceOf(CohereScoringException.class)
        .hasCause(null);
  }

  @Test
  void rejectsMissingAndExtraHydrationIds() {
    for (Map<String, String> hydrated :
        List.of(Map.of("alpha", "a"), Map.of("alpha", "a", "beta", "b", "extra", "c"))) {
      assertThatThrownBy(
              () ->
                  builder()
                      .documentMapper((query, window, context) -> hydrated)
                      .build()
                      .score("query", candidates, context()))
          .isInstanceOf(CohereScoringException.class)
          .hasMessageContaining("exactly");
    }
    assertThat(calls).hasValue(0);
  }

  @Test
  void enforcesCandidateDocumentAndEncodedRequestLimitsBeforeHttp() {
    assertThatThrownBy(
            () -> builder().maxDocuments(1).build().score("query", candidates, context()))
        .isInstanceOf(CohereScoringException.class)
        .hasMessageContaining("count limit");
    assertThatThrownBy(
            () -> builder().maxDocumentChars(1).build().score("query", candidates, context()))
        .isInstanceOf(CohereScoringException.class)
        .hasMessageContaining("character limit");
    assertThatThrownBy(
            () ->
                builder()
                    .maxRequestBytes(32)
                    .build()
                    .score("escaped \" query é".repeat(100), candidates, context()))
        .isInstanceOf(CohereScoringException.class)
        .hasCause(null);
    assertThat(calls).hasValue(0);
  }

  @Test
  void boundsChunkedResponseWhileReadingIncludingErrorBody() {
    response = TOKEN.repeat(100_000);
    for (int code : List.of(200, 401)) {
      status = code;
      assertThatThrownBy(
              () -> builder().maxResponseBytes(128).build().score("query", candidates, context()))
          .isInstanceOf(CohereScoringException.class)
          .hasCause(null)
          .hasMessageNotContaining(TOKEN);
    }
  }

  @Test
  void sanitizesHttpErrorsAndCallbackExceptionsIncludingFullStackTrace() throws Exception {
    response = TOKEN;
    status = 429;
    try {
      builder().build().score("query", candidates, context());
      throw new AssertionError("expected scoring error");
    } catch (CohereScoringException e) {
      assertThat(e.statusCode()).isEqualTo(429);
      assertSanitized(e);
    }
    assertThatThrownBy(
            () ->
                builder()
                    .bearerToken(
                        () -> {
                          throw new IllegalArgumentException(TOKEN);
                        })
                    .build()
                    .score("query", candidates, context()))
        .isInstanceOf(CohereScoringException.class)
        .hasCause(null)
        .hasMessageNotContaining(TOKEN);
    assertThatThrownBy(
            () ->
                builder()
                    .queryMapper(
                        (query, context) -> {
                          throw new IOException(TOKEN);
                        })
                    .build()
                    .score("query", candidates, context()))
        .isInstanceOf(CohereScoringException.class)
        .hasCause(null)
        .hasMessageNotContaining(TOKEN);
    assertThatThrownBy(
            () ->
                builder()
                    .documentMapper(
                        (query, window, context) -> {
                          throw new IOException(TOKEN);
                        })
                    .build()
                    .score("query", candidates, context()))
        .isInstanceOf(CohereScoringException.class)
        .hasCause(null)
        .hasMessageNotContaining(TOKEN);
  }

  @Test
  void rejectsInvalidBearerHeaderWithoutExposingToken() {
    assertThatThrownBy(
            () ->
                builder()
                    .bearerToken(() -> TOKEN + "\r\nInjected: value")
                    .build()
                    .score("query", candidates, context()))
        .isInstanceOf(CohereScoringException.class)
        .hasCause(null)
        .hasMessageNotContaining(TOKEN);
    assertThat(calls).hasValue(0);
  }

  @Test
  void cancelsDelayedHeadersAtRemainingDeadline() {
    headerDelayMillis = 1_000;
    long start = System.nanoTime();
    assertThatThrownBy(
            () ->
                builder()
                    .build()
                    .score("query", candidates, RankingContext.of(2, Duration.ofMillis(150))))
        .isInstanceOf(CohereScoringException.class)
        .hasCause(null);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void cancelsStalledBodyAtRemainingDeadline() {
    bodyDelayMillis = 1_000;
    long start = System.nanoTime();
    assertThatThrownBy(
            () ->
                builder()
                    .build()
                    .score("query", candidates, RankingContext.of(2, Duration.ofMillis(150))))
        .isInstanceOf(CohereScoringException.class)
        .hasCause(null);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void hydrationConsumesTheSameBudgetAndPreventsLateHttp() {
    assertThatThrownBy(
            () ->
                builder()
                    .documentMapper(
                        (query, window, context) -> {
                          Thread.sleep(100);
                          return Map.of("alpha", "a", "beta", "b");
                        })
                    .build()
                    .score("query", candidates, RankingContext.of(2, Duration.ofMillis(20))))
        .isInstanceOf(CohereScoringException.class)
        .hasMessageContaining("budget exhausted");
    assertThat(calls).hasValue(0);
  }

  @Test
  void interruptionCancelsHttpAndRestoresInterruptFlag() throws Exception {
    headerDelayMillis = 5_000;
    requestArrived = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    Thread worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    builder().build().score("query", candidates, context());
                  } catch (Throwable e) {
                    failure.set(e);
                    interrupted.set(Thread.currentThread().isInterrupted());
                  }
                });
    assertThat(requestArrived.await(3, TimeUnit.SECONDS)).isTrue();
    worker.interrupt();
    worker.join(2_000);
    assertThat(worker.isAlive()).isFalse();
    assertThat(failure.get()).isInstanceOf(InterruptedException.class).hasCause(null);
    assertThat(interrupted).isTrue();
  }

  @Test
  void preexistingInterruptionAndExpiredBudgetSkipMapping() {
    AtomicInteger mappings = new AtomicInteger();
    CohereBatchScorer<String, String> scorer =
        builder()
            .queryMapper(
                query -> {
                  mappings.incrementAndGet();
                  return query;
                })
            .build();
    assertThatThrownBy(() -> scorer.score("query", candidates, RankingContext.of(2, Duration.ZERO)))
        .isInstanceOf(CohereScoringException.class);
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> scorer.score("query", candidates, context()))
          .isInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
    assertThat(mappings).hasValue(0);
    assertThat(calls).hasValue(0);
  }

  @Test
  void emptyWindowSkipsAllCallbacksAndDuplicateIdsAreRejected() throws Exception {
    assertThat(
            builder()
                .documentMapper(
                    (query, window, context) -> {
                      throw new AssertionError("must not hydrate");
                    })
                .build()
                .score("query", List.of(), context()))
        .isEmpty();
    assertThatThrownBy(
            () ->
                builder()
                    .build()
                    .score(
                        "query", List.of(candidates.getFirst(), candidates.getFirst()), context()))
        .isInstanceOf(CohereScoringException.class)
        .hasMessageContaining("unique");
    assertThat(calls).hasValue(0);
  }

  @Test
  void validatesEndpointConfigurationAndDisablesCredentialRedirects() {
    assertThatThrownBy(() -> builder().endpoint(URI.create("http://example.com/v2/rerank")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder().endpoint(URI.create("https://token@example.com/rerank")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("token@");
    assertThatThrownBy(
            () -> builder().endpoint(URI.create("https://example.com/rerank?secret=" + TOKEN)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(TOKEN);
    try (HttpClient redirecting =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()) {
      assertThatThrownBy(() -> CohereBatchScorer.builder(redirecting, mapper))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("redirects");
    }
    assertThatThrownBy(() -> CohereBatchScorer.builder(client, mapper).build())
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> builder().model(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder().maxDocuments(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder().maxDocumentChars(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder().maxRequestBytes(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder().maxResponseBytes(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void followsNoRedirectsAndApplicationCanReuseClient() throws Exception {
    status = 302;
    response = "";
    assertThatThrownBy(() -> builder().build().score("query", candidates, context()))
        .isInstanceOf(CohereScoringException.class);
    status = 200;
    response = VALID;
    assertThat(builder().build().score("query", candidates, context())).hasSize(2);
    assertThat(calls).hasValue(2);
  }

  private CohereBatchScorer.Builder<String, String> builder() {
    return CohereBatchScorer.<String, String>builder(client, mapper)
        .endpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v2/rerank"))
        .model("explicit-model")
        .bearerToken(() -> TOKEN)
        .queryMapper(query -> query)
        .documentMapper(
            (query, window, context) -> Map.of("alpha", "first text", "beta", "second text"));
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      calls.incrementAndGet();
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      if (requestArrived != null) {
        requestArrived.countDown();
      }
      if (headerDelayMillis > 0) {
        Thread.sleep(headerDelayMillis);
      }
      if (status == 302) {
        exchange.getResponseHeaders().set("Location", "/redirected");
      }
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, 0);
      if (bodyDelayMillis > 0) {
        exchange.getResponseBody().write(' ');
        exchange.getResponseBody().flush();
        Thread.sleep(bodyDelayMillis);
      }
      byte[] body = response.getBytes(StandardCharsets.UTF_8);
      for (int offset = 0; offset < body.length; offset += 256) {
        exchange.getResponseBody().write(body, offset, Math.min(256, body.length - offset));
        exchange.getResponseBody().flush();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void assertSanitized(Throwable failure) {
    StringWriter trace = new StringWriter();
    failure.printStackTrace(new PrintWriter(trace));
    assertThat(trace.toString()).doesNotContain(TOKEN);
    assertThat(failure.getCause()).isNull();
    assertThat(failure.getSuppressed()).isEmpty();
  }

  private static RankedCandidate<String> candidate(String id, double score) {
    return new RankedCandidate<>(
        id, score, List.of(new SourceContribution("search", 1, score)), Map.of());
  }

  private static RankingContext context() {
    return RankingContext.of(2, Duration.ofSeconds(5));
  }
}
