package io.github.ahmetyasarkaplan.kervan.opensearch;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.ScoreDirection;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import jakarta.json.JsonException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.opensearch.client.json.UnionDeserializer.AmbiguousUnionException;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.util.MissingRequiredPropertyException;

/**
 * Adapts native OpenSearch search into one ordered Kervan provider result.
 *
 * <p>The application owns the supplied client and transport. The adapter shares that transport with
 * an asynchronous client and never closes either. Server timeouts and bounded future waits apply to
 * each search; cancelling a future requests transport cancellation where supported, without
 * guaranteeing termination of an in-flight server operation. Native query callbacks must return
 * promptly. Native result order is retained and numeric scores are oriented for score-based fusion.
 *
 * @param <Q> application query type
 * @param <ID> canonical identifier type
 * @param <T> native document type
 */
public final class OpenSearchCandidateRetriever<Q, ID, T> implements CandidateRetriever<Q, ID> {
  private final OpenSearchAsyncClient client;
  private final Class<T> documentType;
  private final OpenSearchSearchRequestFactory<Q> requestFactory;
  private final Function<Hit<T>, ID> idMapper;
  private final Function<Hit<T>, Map<String, Object>> attributesMapper;
  private final ScoreDirection scoreDirection;

  private OpenSearchCandidateRetriever(Builder<Q, ID, T> builder) {
    client =
        new OpenSearchAsyncClient(builder.client._transport(), builder.client._transportOptions());
    documentType = builder.documentType;
    requestFactory = builder.requestFactory;
    idMapper = builder.idMapper;
    attributesMapper = builder.attributesMapper;
    scoreDirection = builder.scoreDirection;
  }

  /**
   * Starts configuration using the official caller-owned client.
   *
   * @param <Q> application query type
   * @param <ID> canonical identifier type
   * @param <T> native document type
   * @param client caller-owned official client
   * @param documentType native source mapping type
   * @return retriever builder
   */
  public static <Q, ID, T> Builder<Q, ID, T> builder(
      OpenSearchClient client, Class<T> documentType) {
    return new Builder<>(client, documentType);
  }

  @Override
  public List<SourceCandidate<ID>> retrieve(Q query, RetrievalContext context) {
    Objects.requireNonNull(context, "context must not be null");
    long started = System.nanoTime();
    long budget;
    try {
      budget = context.timeBudget().toNanos();
    } catch (ArithmeticException ignored) {
      budget = Long.MAX_VALUE;
    }
    SearchRequest request;
    try {
      SearchRequest.Builder builder = Objects.requireNonNull(requestFactory.create(query, context));
      long remaining = budget - (System.nanoTime() - started);
      if (remaining <= 0) {
        throw failure(OpenSearchRetrievalException.Reason.TIMED_OUT);
      }
      long millis = remaining / 1_000_000 + (remaining % 1_000_000 == 0 ? 0 : 1);
      request =
          builder
              .size(context.candidateLimit())
              .timeout(millis + "ms")
              .allowPartialSearchResults(false)
              .build();
    } catch (OpenSearchRetrievalException e) {
      throw e;
    } catch (RuntimeException e) {
      throw failure(OpenSearchRetrievalException.Reason.INVALID_REQUEST);
    }

    CompletableFuture<SearchResponse<T>> future;
    try {
      future = client.search(request, documentType);
    } catch (IOException | RuntimeException e) {
      throw classify(e);
    }
    SearchResponse<T> response;
    try {
      long remaining = budget - (System.nanoTime() - started);
      if (remaining <= 0) {
        throw new TimeoutException();
      }
      response = future.get(remaining, TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw failure(OpenSearchRetrievalException.Reason.TIMED_OUT);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw failure(OpenSearchRetrievalException.Reason.INTERRUPTED);
    } catch (ExecutionException e) {
      throw classify(e.getCause());
    } catch (RuntimeException e) {
      throw classify(e);
    }

    try {
      if (response.timedOut()) {
        throw failure(OpenSearchRetrievalException.Reason.TIMED_OUT);
      }
      if (response.shards().failed() > 0) {
        throw failure(OpenSearchRetrievalException.Reason.REMOTE_FAILURE);
      }
      List<SourceCandidate<ID>> candidates = new ArrayList<>();
      for (Hit<T> hit : response.hits().hits()) {
        if (candidates.size() == context.candidateLimit()) {
          break;
        }
        if (budget - (System.nanoTime() - started) <= 0) {
          throw failure(OpenSearchRetrievalException.Reason.TIMED_OUT);
        }
        candidates.add(
            new SourceCandidate<>(
                Objects.requireNonNull(idMapper.apply(hit)),
                scoreDirection.orient(Objects.requireNonNull(hit.score())),
                Objects.requireNonNull(attributesMapper.apply(hit))));
      }
      if (budget - (System.nanoTime() - started) <= 0) {
        throw failure(OpenSearchRetrievalException.Reason.TIMED_OUT);
      }
      return List.copyOf(candidates);
    } catch (OpenSearchRetrievalException e) {
      throw e;
    } catch (RuntimeException e) {
      throw failure(OpenSearchRetrievalException.Reason.INVALID_RESPONSE);
    }
  }

  private static OpenSearchRetrievalException classify(Throwable failure) {
    // Native transports can wrap parser failures in IOException/TransportException. Inspect only
    // types and never retain the native cause or its possibly sensitive message.
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
      if (current instanceof OpenSearchException) {
        return failure(OpenSearchRetrievalException.Reason.REMOTE_FAILURE);
      }
      if (current instanceof JsonException
          || current instanceof MissingRequiredPropertyException
          || current instanceof AmbiguousUnionException) {
        return failure(OpenSearchRetrievalException.Reason.INVALID_RESPONSE);
      }
    }
    return failure(OpenSearchRetrievalException.Reason.TRANSPORT_FAILURE);
  }

  private static OpenSearchRetrievalException failure(OpenSearchRetrievalException.Reason reason) {
    return new OpenSearchRetrievalException(reason);
  }

  /**
   * Configures native query callbacks and canonical candidate mapping.
   *
   * @param <Q> application query type
   * @param <ID> canonical identifier type
   * @param <T> native document type
   */
  public static final class Builder<Q, ID, T> {
    private final OpenSearchClient client;
    private final Class<T> documentType;
    private OpenSearchSearchRequestFactory<Q> requestFactory;
    private Function<Hit<T>, ID> idMapper;
    private Function<Hit<T>, Map<String, Object>> attributesMapper = ignored -> Map.of();
    private ScoreDirection scoreDirection;

    private Builder(OpenSearchClient client, Class<T> documentType) {
      this.client = Objects.requireNonNull(client, "client");
      this.documentType = Objects.requireNonNull(documentType, "documentType");
    }

    /**
     * Supplies a native OpenSearch query builder.
     *
     * @param requestFactory native request callback
     * @return this builder
     */
    public Builder<Q, ID, T> requestFactory(OpenSearchSearchRequestFactory<Q> requestFactory) {
      this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory");
      return this;
    }

    /**
     * Maps native hits into application canonical IDs.
     *
     * @param idMapper nonnull canonical ID mapping
     * @return this builder
     */
    public Builder<Q, ID, T> idMapper(Function<Hit<T>, ID> idMapper) {
      this.idMapper = Objects.requireNonNull(idMapper, "idMapper");
      return this;
    }

    /**
     * Supplies small application attributes for ranking or diagnostics.
     *
     * @param attributesMapper nonnull attribute mapping
     * @return this builder
     */
    public Builder<Q, ID, T> attributesMapper(
        Function<Hit<T>, Map<String, Object>> attributesMapper) {
      this.attributesMapper = Objects.requireNonNull(attributesMapper, "attributesMapper");
      return this;
    }

    /**
     * Declares native numeric score direction; ordinary OpenSearch relevance uses higher-is-better.
     *
     * @param scoreDirection explicit native score direction
     * @return this builder
     */
    public Builder<Q, ID, T> scoreDirection(ScoreDirection scoreDirection) {
      this.scoreDirection = Objects.requireNonNull(scoreDirection, "scoreDirection");
      return this;
    }

    /**
     * Validates required query, identity, and score direction settings.
     *
     * @return configured retriever
     */
    public OpenSearchCandidateRetriever<Q, ID, T> build() {
      if (requestFactory == null || idMapper == null || scoreDirection == null) {
        throw new IllegalStateException("requestFactory, idMapper and scoreDirection are required");
      }
      return new OpenSearchCandidateRetriever<>(this);
    }
  }
}
