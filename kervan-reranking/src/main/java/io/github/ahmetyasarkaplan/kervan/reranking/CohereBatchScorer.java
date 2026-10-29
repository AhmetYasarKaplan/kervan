package io.github.ahmetyasarkaplan.kervan.reranking;

import io.github.ahmetyasarkaplan.kervan.api.BatchScorer;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Optional Cohere v2 rerank adapter with bulk application-owned hydration and bounded HTTP I/O.
 *
 * <p>The application owns the supplied HTTP client and JSON mapper. This scorer never closes them,
 * retries, logs payloads, or creates an executor. Configure the client without redirects. Query and
 * document callbacks run on the calling thread and must honor the context budget and interruption;
 * budget expiration is checked before and after callbacks. HTTP transfer, including response body
 * consumption, is cancelled at the remaining deadline.
 *
 * @param <Q> query input type
 * @param <ID> canonical candidate identifier type
 */
public final class CohereBatchScorer<Q, ID> implements BatchScorer<Q, ID> {
  private final Builder<Q, ID> config;

  private CohereBatchScorer(Builder<Q, ID> builder) {
    config = new Builder<>(builder);
  }

  /**
   * Creates an adapter builder with bounded defaults and no default model or credentials.
   *
   * @param client application-owned client with redirects disabled
   * @param mapper application-owned Jackson 3 JSON mapper
   * @param <Q> query input type
   * @param <ID> canonical identifier type
   * @return builder
   */
  public static <Q, ID> Builder<Q, ID> builder(HttpClient client, JsonMapper mapper) {
    return new Builder<>(client, mapper);
  }

  @Override
  public Map<ID, Double> score(
      Q query, List<RankedCandidate<ID>> candidates, RankingContext context)
      throws CohereScoringException, InterruptedException {
    Objects.requireNonNull(context, "context must not be null");
    CompletableFuture<HttpResponse<byte[]>> pending = null;
    try {
      checkBudget(context);
      List<RankedCandidate<ID>> window = List.copyOf(candidates);
      if (window.size() > config.maxDocuments) {
        throw new CohereScoringException("Cohere candidate count limit exceeded");
      }
      if (window.isEmpty()) {
        return Map.of();
      }
      var ids = new HashSet<ID>();
      for (RankedCandidate<ID> candidate : window) {
        if (!ids.add(candidate.id())) {
          throw new CohereScoringException("Cohere candidate IDs must be unique");
        }
      }
      String queryText = config.queryMapper.map(query, context);
      checkBudget(context);
      Map<ID, String> hydrated = config.documentMapper.map(query, window, context);
      checkBudget(context);
      if (queryText == null || hydrated == null || !hydrated.keySet().equals(ids)) {
        throw new CohereScoringException("Cohere mapping must cover exactly the selected IDs");
      }
      var documents = new ArrayList<String>(window.size());
      for (RankedCandidate<ID> candidate : window) {
        String document = hydrated.get(candidate.id());
        if (document == null || document.length() > config.maxDocumentChars) {
          throw new CohereScoringException("Cohere document is missing or exceeds character limit");
        }
        documents.add(document);
      }
      var bytes = new LimitedOutputStream(config.maxRequestBytes);
      config.mapper.writeValue(
          bytes, Map.of("model", config.model, "query", queryText, "documents", documents));
      checkBudget(context);
      String token = config.bearerToken.get();
      if (token == null || token.isBlank()) {
        throw new CohereScoringException("Cohere bearer token is missing");
      }
      Duration remaining = checkBudget(context);
      HttpRequest request =
          HttpRequest.newBuilder(config.endpoint)
              .timeout(remaining)
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()))
              .build();
      pending =
          config.client.sendAsync(
              request, ignored -> new BoundedBodySubscriber(config.maxResponseBytes));
      HttpResponse<byte[]> response =
          pending.get(checkBudget(context).toNanos(), TimeUnit.NANOSECONDS);
      checkBudget(context);
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new CohereScoringException(
            "Cohere HTTP request failed with status " + response.statusCode(),
            response.statusCode());
      }
      Map<ID, Double> result = parseScores(response.body(), window);
      checkBudget(context);
      return result;
    } catch (InterruptedException e) {
      if (pending != null) {
        pending.cancel(true);
      }
      Thread.currentThread().interrupt();
      throw new InterruptedException("Cohere scoring interrupted");
    } catch (TimeoutException e) {
      throw new CohereScoringException("Cohere ranking budget exhausted");
    } catch (CohereScoringException e) {
      throw e;
    } catch (ExecutionException e) {
      throw new CohereScoringException("Cohere HTTP transfer failed or exceeded response limit");
    } catch (Exception e) {
      throw new CohereScoringException(
          "Cohere mapping, request encoding, or response validation failed");
    } finally {
      if (pending != null && !pending.isDone()) {
        pending.cancel(true);
      }
    }
  }

  private Map<ID, Double> parseScores(byte[] body, List<RankedCandidate<ID>> window)
      throws CohereScoringException {
    JsonNode root =
        config.mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
    JsonNode results = root == null ? null : root.get("results");
    if (results == null || !results.isArray() || results.size() != window.size()) {
      throw new CohereScoringException("Cohere response must score every selected document");
    }
    var scores = new LinkedHashMap<ID, Double>();
    for (JsonNode result : results) {
      JsonNode index = result.get("index");
      JsonNode relevance = result.get("relevance_score");
      if (index == null
          || !index.isIntegralNumber()
          || !index.canConvertToInt()
          || index.intValue() < 0
          || index.intValue() >= window.size()
          || relevance == null
          || !relevance.isNumber()
          || !Double.isFinite(relevance.doubleValue())) {
        throw new CohereScoringException("Cohere response contains an invalid result");
      }
      if (scores.put(window.get(index.intValue()).id(), relevance.doubleValue()) != null) {
        throw new CohereScoringException("Cohere response contains duplicate result indices");
      }
    }
    return Map.copyOf(scores);
  }

  private static Duration checkBudget(RankingContext context)
      throws CohereScoringException, InterruptedException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedException("Cohere scoring interrupted");
    }
    Duration remaining = context.remainingBudget();
    if (remaining.isZero()) {
      throw new CohereScoringException("Cohere ranking budget exhausted");
    }
    return remaining;
  }

  /**
   * Configures an immutable scorer. Defaults: 1,000 documents, 100,000 Java characters per
   * document, 4 MiB encoded request, 1 MiB response. The model must be explicitly selected.
   *
   * @param <Q> query input type
   * @param <ID> canonical identifier type
   */
  public static final class Builder<Q, ID> {
    private final HttpClient client;
    private final JsonMapper mapper;
    private URI endpoint = URI.create("https://api.cohere.com/v2/rerank");
    private String model;
    private Supplier<String> bearerToken;
    private QueryMapper<Q> queryMapper;
    private BatchDocumentMapper<Q, ID> documentMapper;
    private int maxDocuments = 1_000;
    private int maxDocumentChars = 100_000;
    private int maxRequestBytes = 4 * 1024 * 1024;
    private int maxResponseBytes = 1024 * 1024;

    private Builder(HttpClient client, JsonMapper mapper) {
      this.client = Objects.requireNonNull(client, "client must not be null");
      this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
      if (client.followRedirects() != HttpClient.Redirect.NEVER) {
        throw new IllegalArgumentException("Cohere HTTP client must disable redirects");
      }
    }

    private Builder(Builder<Q, ID> source) {
      this(source.client, source.mapper);
      endpoint = source.endpoint;
      model = source.model;
      bearerToken = source.bearerToken;
      queryMapper = source.queryMapper;
      documentMapper = source.documentMapper;
      maxDocuments = source.maxDocuments;
      maxDocumentChars = source.maxDocumentChars;
      maxRequestBytes = source.maxRequestBytes;
      maxResponseBytes = source.maxResponseBytes;
    }

    /**
     * Sets the complete HTTPS endpoint, or HTTP for localhost/127.0.0.1/[::1] local serving.
     *
     * @param value absolute endpoint without user info, query, or fragment
     * @return this builder
     */
    public Builder<Q, ID> endpoint(URI value) {
      Objects.requireNonNull(value, "endpoint must not be null");
      String host = value.getHost();
      boolean local =
          "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
      if (host == null
          || value.getUserInfo() != null
          || value.getQuery() != null
          || value.getFragment() != null
          || !("https".equalsIgnoreCase(value.getScheme())
              || (local && "http".equalsIgnoreCase(value.getScheme())))) {
        throw new IllegalArgumentException(
            "Cohere endpoint must use HTTPS or loopback HTTP without credentials, query, or fragment");
      }
      endpoint = value;
      return this;
    }

    /**
     * Sets the model selected by the application.
     *
     * @param value explicit provider model identifier
     * @return this builder
     */
    public Builder<Q, ID> model(String value) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("Cohere model must not be blank");
      }
      model = value;
      return this;
    }

    /**
     * Sets a credential supplier.
     *
     * @param value application credential supplier, invoked per request
     * @return this builder
     */
    public Builder<Q, ID> bearerToken(Supplier<String> value) {
      bearerToken = Objects.requireNonNull(value, "bearerToken must not be null");
      return this;
    }

    /**
     * Sets the query mapper with access to the decreasing budget.
     *
     * @param value application query text mapper
     * @return this builder
     */
    public Builder<Q, ID> queryMapper(QueryMapper<Q> value) {
      queryMapper = Objects.requireNonNull(value, "queryMapper must not be null");
      return this;
    }

    /**
     * Sets a simple, non-blocking query mapper.
     *
     * @param value application query text mapper
     * @return this builder
     */
    public Builder<Q, ID> queryMapper(Function<Q, String> value) {
      Objects.requireNonNull(value, "queryMapper must not be null");
      queryMapper = (query, context) -> value.apply(query);
      return this;
    }

    /**
     * Sets the bulk hydration callback.
     *
     * @param value application bulk document hydration and text mapper
     * @return this builder
     */
    public Builder<Q, ID> documentMapper(BatchDocumentMapper<Q, ID> value) {
      documentMapper = Objects.requireNonNull(value, "documentMapper must not be null");
      return this;
    }

    /**
     * Limits the candidate count.
     *
     * @param value positive maximum documents per call
     * @return this builder
     */
    public Builder<Q, ID> maxDocuments(int value) {
      maxDocuments = positive(value);
      return this;
    }

    /**
     * Limits individual documents.
     *
     * @param value positive maximum Java characters per document
     * @return this builder
     */
    public Builder<Q, ID> maxDocumentChars(int value) {
      maxDocumentChars = positive(value);
      return this;
    }

    /**
     * Limits encoded requests during serialization.
     *
     * @param value positive maximum encoded UTF-8 JSON request bytes
     * @return this builder
     */
    public Builder<Q, ID> maxRequestBytes(int value) {
      maxRequestBytes = positive(value);
      return this;
    }

    /**
     * Limits responses during transfer.
     *
     * @param value positive maximum response bytes, enforced during transfer
     * @return this builder
     */
    public Builder<Q, ID> maxResponseBytes(int value) {
      maxResponseBytes = positive(value);
      return this;
    }

    /**
     * Builds a scorer with an immutable configuration snapshot.
     *
     * @return scorer with a snapshot of this configuration
     */
    public CohereBatchScorer<Q, ID> build() {
      if (model == null || bearerToken == null || queryMapper == null || documentMapper == null) {
        throw new IllegalStateException(
            "Cohere model, bearerToken, queryMapper and documentMapper are required");
      }
      return new CohereBatchScorer<>(this);
    }

    private static int positive(int value) {
      if (value <= 0) {
        throw new IllegalArgumentException("Cohere limits must be positive");
      }
      return value;
    }
  }

  private static final class LimitedOutputStream extends OutputStream {
    private final int limit;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private LimitedOutputStream(int limit) {
      this.limit = limit;
    }

    @Override
    public void write(int value) throws IOException {
      if (output.size() == limit) {
        throw new IOException("Cohere request byte limit exceeded");
      }
      output.write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      if (length > limit - output.size()) {
        throw new IOException("Cohere request byte limit exceeded");
      }
      output.write(bytes, offset, length);
    }

    private byte[] toByteArray() {
      return output.toByteArray();
    }
  }

  private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    private BoundedBodySubscriber(int limit) {
      this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
      if (subscription != null) {
        value.cancel();
        return;
      }
      subscription = value;
      value.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > limit - output.size()) {
          subscription.cancel();
          body.completeExceptionally(new IOException("Cohere response byte limit exceeded"));
          return;
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        output.writeBytes(bytes);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      body.completeExceptionally(new IOException("Cohere response transfer failed"));
    }

    @Override
    public void onComplete() {
      body.complete(output.toByteArray());
    }
  }
}
