package io.github.ahmetyasarkaplan.kervan.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class RecommendationHttpRetrieverTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void mapsNativeIdsToCanonicalIdsAndHonorsCandidateLimit() throws IOException {
    AtomicReference<String> query = new AtomicReference<>();
    startServer(200, "camera-kit\t0.95\ntripod\t0.80\n", rawQuery -> query.set(rawQuery));

    var results =
        retriever()
            .retrieve(
                new ProductQuery("camera lens", List.of(1.0f, 0.0f, 0.0f)),
                RetrievalContext.of(1, Duration.ofSeconds(1)));

    assertThat(query.get()).isEqualTo("q=camera+lens&limit=1");
    assertThat(results)
        .extracting(candidate -> candidate.id())
        .containsExactly("catalog:camera-kit");
    assertThat(results.getFirst().score()).isEqualTo(0.95);
  }

  @Test
  void rejectsMalformedScoresWithoutExposingResponseBody() throws IOException {
    startServer(200, "private-id\tNaN\n", ignored -> {});

    assertThatThrownBy(
            () ->
                retriever()
                    .retrieve(
                        new ProductQuery("camera", List.of(1.0f, 0.0f, 0.0f)),
                        RetrievalContext.of(2, Duration.ofSeconds(1))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Invalid recommendation response")
        .hasNoCause();
  }

  @Test
  void rejectsRemoteFailureWithoutExposingResponseBody() throws IOException {
    startServer(503, "private failure detail", ignored -> {});

    assertThatThrownBy(
            () ->
                retriever()
                    .retrieve(
                        new ProductQuery("camera", List.of(1.0f, 0.0f, 0.0f)),
                        RetrievalContext.of(2, Duration.ofSeconds(1))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Recommendation provider returned HTTP 503")
        .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsOversizedFixedLengthAndChunkedResponses(boolean chunked) throws IOException {
    startServer(200, "private-id".repeat(7000) + "\t0.5\n", ignored -> {}, chunked);

    assertResponseTooLarge();
  }

  @Test
  void limitsUtf8BytesInsteadOfDecodedCharacters() throws IOException {
    startServer(200, "é".repeat(33 * 1024) + "\t0.5\n", ignored -> {});

    assertResponseTooLarge();
  }

  @Test
  void acceptsResponseAtTheByteLimit() throws IOException {
    String nativeId = "p".repeat(64 * 1024 - 5);
    startServer(200, nativeId + "\t0.5\n", ignored -> {});

    var results =
        retriever()
            .retrieve(
                new ProductQuery("camera", List.of(1.0f, 0.0f, 0.0f)),
                RetrievalContext.of(1, Duration.ofSeconds(2)));

    assertThat(results)
        .extracting(candidate -> candidate.id())
        .containsExactly("catalog:" + nativeId);
  }

  @Test
  void rejectsOversizedStreamBeforeTheServerFinishesSending() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    startServer(
        exchange -> {
          try (exchange) {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[64 * 1024 + 1]);
            exchange.getResponseBody().flush();
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });

    try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
      var result = worker.submit(this::assertResponseTooLarge);
      try {
        result.get(3, TimeUnit.SECONDS);
      } finally {
        release.countDown();
        result.cancel(true);
      }
    }
  }

  private void assertResponseTooLarge() {
    assertThatThrownBy(
            () ->
                retriever()
                    .retrieve(
                        new ProductQuery("camera", List.of(1.0f, 0.0f, 0.0f)),
                        RetrievalContext.of(1, Duration.ofSeconds(2))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Recommendation response is too large")
        .hasNoCause();
  }

  private RecommendationHttpRetriever retriever() {
    return new RecommendationHttpRetriever(
        java.net.URI.create(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/recommendations"));
  }

  private void startServer(
      int status, String body, java.util.function.Consumer<String> queryObserver)
      throws IOException {
    startServer(status, body, queryObserver, false);
  }

  private void startServer(
      int status, String body, java.util.function.Consumer<String> queryObserver, boolean chunked)
      throws IOException {
    startServer(
        exchange -> {
          queryObserver.accept(exchange.getRequestURI().getRawQuery());
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, chunked ? 0 : bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          }
        });
  }

  private void startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/recommendations", handler);
    server.start();
  }
}
