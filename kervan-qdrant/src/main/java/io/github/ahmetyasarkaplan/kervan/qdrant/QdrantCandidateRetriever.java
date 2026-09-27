package io.github.ahmetyasarkaplan.kervan.qdrant;

import com.google.common.util.concurrent.ListenableFuture;
import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.ScoreDirection;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Adapts one native Qdrant query response into an ordered Kervan provider list.
 *
 * <p>The application owns the client, native query, canonical ID mapping, and score direction.
 * Qdrant can report similarity or distance depending on the collection metric and query type, so
 * score direction must be chosen explicitly before using score-based fusion.
 *
 * @param <Q> application query type
 * @param <ID> canonical candidate identifier type
 */
public final class QdrantCandidateRetriever<Q, ID> implements CandidateRetriever<Q, ID> {

  private final QdrantClient client;
  private final QdrantQueryRequestFactory<Q> requestFactory;
  private final Function<ScoredPoint, ID> idMapper;
  private final Function<ScoredPoint, Map<String, Object>> attributesMapper;
  private final ScoreDirection scoreDirection;

  private QdrantCandidateRetriever(Builder<Q, ID> builder) {
    client = builder.client;
    requestFactory = builder.requestFactory;
    idMapper = builder.idMapper;
    attributesMapper = builder.attributesMapper;
    scoreDirection = builder.scoreDirection;
  }

  /**
   * Starts a retriever configuration using a caller-owned Qdrant client.
   *
   * @param <Q> application query type
   * @param <ID> canonical candidate identifier type
   * @param client caller-owned Qdrant client
   * @return retriever builder
   */
  public static <Q, ID> Builder<Q, ID> builder(QdrantClient client) {
    return new Builder<>(client);
  }

  @Override
  public List<SourceCandidate<ID>> retrieve(Q query, RetrievalContext context) {
    Objects.requireNonNull(context, "context must not be null");
    QueryPoints.Builder requestBuilder =
        Objects.requireNonNull(
            requestFactory.create(query, context), "requestFactory must return a builder");
    QueryPoints request = requestBuilder.setLimit(context.candidateLimit()).build();

    ListenableFuture<List<ScoredPoint>> future;
    try {
      future = client.queryAsync(request, context.timeBudget());
    } catch (RuntimeException e) {
      throw failureFor(e);
    }
    List<ScoredPoint> points;
    try {
      points = future.get(context.timeBudget().toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new QdrantRetrievalException(QdrantRetrievalException.Reason.TIMED_OUT);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new QdrantRetrievalException(QdrantRetrievalException.Reason.INTERRUPTED);
    } catch (ExecutionException e) {
      throw failureFor(e.getCause());
    }

    if (points == null) {
      throw new QdrantRetrievalException(QdrantRetrievalException.Reason.INVALID_RESPONSE);
    }
    List<SourceCandidate<ID>> candidates = new ArrayList<>();
    for (ScoredPoint point : points) {
      if (candidates.size() == context.candidateLimit()) {
        break;
      }
      try {
        ID id = Objects.requireNonNull(idMapper.apply(point));
        Map<String, Object> attributes = Objects.requireNonNull(attributesMapper.apply(point));
        candidates.add(
            new SourceCandidate<>(id, scoreDirection.orient(point.getScore()), attributes));
      } catch (RuntimeException e) {
        throw new QdrantRetrievalException(QdrantRetrievalException.Reason.INVALID_RESPONSE);
      }
    }
    return List.copyOf(candidates);
  }

  private static QdrantRetrievalException failureFor(Throwable failure) {
    Status.Code code = Status.fromThrowable(failure).getCode();
    QdrantRetrievalException.Reason reason =
        switch (code) {
          case UNAVAILABLE -> QdrantRetrievalException.Reason.TRANSPORT_FAILURE;
          case DEADLINE_EXCEEDED -> QdrantRetrievalException.Reason.TIMED_OUT;
          default -> QdrantRetrievalException.Reason.REMOTE_FAILURE;
        };
    return new QdrantRetrievalException(reason);
  }

  /**
   * Configures provider-native requests and canonical candidate mapping.
   *
   * @param <Q> application query type
   * @param <ID> canonical candidate identifier type
   */
  public static final class Builder<Q, ID> {
    private final QdrantClient client;
    private QdrantQueryRequestFactory<Q> requestFactory;
    private Function<ScoredPoint, ID> idMapper;
    private Function<ScoredPoint, Map<String, Object>> attributesMapper = ignored -> Map.of();
    private ScoreDirection scoreDirection;

    private Builder(QdrantClient client) {
      this.client = Objects.requireNonNull(client, "client must not be null");
    }

    /**
     * Supplies a provider-native query factory. It may use Qdrant filters and hybrid prefetches.
     *
     * @param requestFactory factory for one Qdrant query
     * @return this builder
     */
    public Builder<Q, ID> requestFactory(QdrantQueryRequestFactory<Q> requestFactory) {
      this.requestFactory =
          Objects.requireNonNull(requestFactory, "requestFactory must not be null");
      return this;
    }

    /**
     * Maps a Qdrant point to the application's canonical candidate ID.
     *
     * @param idMapper mapper that must return a nonnull canonical ID
     * @return this builder
     */
    public Builder<Q, ID> idMapper(Function<ScoredPoint, ID> idMapper) {
      this.idMapper = Objects.requireNonNull(idMapper, "idMapper must not be null");
      return this;
    }

    /**
     * Maps a Qdrant point to small application-owned ranking attributes.
     *
     * @param attributesMapper mapper that must return a nonnull map
     * @return this builder
     */
    public Builder<Q, ID> attributesMapper(
        Function<ScoredPoint, Map<String, Object>> attributesMapper) {
      this.attributesMapper =
          Objects.requireNonNull(attributesMapper, "attributesMapper must not be null");
      return this;
    }

    /**
     * Declares whether the Qdrant score is similarity or distance. Distance is negated before
     * score-based fusion; Qdrant's original result order is always retained.
     *
     * @param scoreDirection direction of the native Qdrant score
     * @return this builder
     */
    public Builder<Q, ID> scoreDirection(ScoreDirection scoreDirection) {
      this.scoreDirection =
          Objects.requireNonNull(scoreDirection, "scoreDirection must not be null");
      return this;
    }

    /**
     * Validates the required native request, ID mapping, and score direction.
     *
     * @return configured retriever
     */
    public QdrantCandidateRetriever<Q, ID> build() {
      if (requestFactory == null || idMapper == null || scoreDirection == null) {
        throw new IllegalStateException("requestFactory, idMapper and scoreDirection are required");
      }
      return new QdrantCandidateRetriever<>(this);
    }
  }
}
