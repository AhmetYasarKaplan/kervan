package io.github.ahmetyasarkaplan.kervan.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Adapts one Elasticsearch search response into an ordered Kervan provider list.
 *
 * @param <Q> application query type
 * @param <ID> canonical candidate identifier type
 * @param <T> Elasticsearch document type
 */
public final class ElasticsearchCandidateRetriever<Q, ID, T> implements CandidateRetriever<Q, ID> {

  private final ElasticsearchClient client;
  private final Class<T> documentType;
  private final ElasticsearchSearchRequestFactory<Q> requestFactory;
  private final Function<Hit<T>, ID> idMapper;
  private final Function<Hit<T>, Map<String, Object>> attributesMapper;

  private ElasticsearchCandidateRetriever(Builder<Q, ID, T> builder) {
    client = builder.client;
    documentType = builder.documentType;
    requestFactory = builder.requestFactory;
    idMapper = builder.idMapper;
    attributesMapper = builder.attributesMapper;
  }

  /**
   * Starts a retriever configuration using a caller-owned Elasticsearch client.
   *
   * @param <Q> application query type
   * @param <ID> canonical candidate identifier type
   * @param <T> Elasticsearch document type
   * @param client caller-owned Elasticsearch client
   * @param documentType document mapping type
   * @return retriever builder
   */
  public static <Q, ID, T> Builder<Q, ID, T> builder(
      ElasticsearchClient client, Class<T> documentType) {
    return new Builder<>(client, documentType);
  }

  @Override
  public List<SourceCandidate<ID>> retrieve(Q query, RetrievalContext context) {
    Objects.requireNonNull(context, "context must not be null");
    SearchRequest.Builder requestBuilder =
        Objects.requireNonNull(
            requestFactory.create(query, context), "requestFactory must return a builder");
    long nanos = context.timeBudget().toNanos();
    long millis = Math.max(1, nanos / 1_000_000 + (nanos % 1_000_000 == 0 ? 0 : 1));
    SearchRequest request =
        requestBuilder
            .size(context.candidateLimit())
            .timeout(millis + "ms")
            .allowPartialSearchResults(false)
            .build();

    SearchResponse<T> response;
    try {
      response = client.search(request, documentType);
    } catch (IOException e) {
      throw new ElasticsearchRetrievalException(
          ElasticsearchRetrievalException.Reason.TRANSPORT_FAILURE);
    } catch (ElasticsearchException e) {
      throw new ElasticsearchRetrievalException(
          ElasticsearchRetrievalException.Reason.REMOTE_FAILURE);
    }

    if (response.timedOut()) {
      throw new ElasticsearchRetrievalException(ElasticsearchRetrievalException.Reason.TIMED_OUT);
    }
    List<SourceCandidate<ID>> candidates = new ArrayList<>();
    for (Hit<T> hit : response.hits().hits()) {
      if (candidates.size() == context.candidateLimit()) {
        break;
      }
      Double score = hit.score();
      if (score == null || !Double.isFinite(score)) {
        throw new ElasticsearchRetrievalException(
            ElasticsearchRetrievalException.Reason.INVALID_RESPONSE);
      }
      try {
        candidates.add(
            new SourceCandidate<>(idMapper.apply(hit), score, attributesMapper.apply(hit)));
      } catch (RuntimeException e) {
        throw new ElasticsearchRetrievalException(
            ElasticsearchRetrievalException.Reason.INVALID_RESPONSE);
      }
    }
    return List.copyOf(candidates);
  }

  /**
   * Configures provider-native requests and canonical ID mapping.
   *
   * @param <Q> application query type
   * @param <ID> canonical candidate identifier type
   * @param <T> Elasticsearch document type
   */
  public static final class Builder<Q, ID, T> {
    private final ElasticsearchClient client;
    private final Class<T> documentType;
    private ElasticsearchSearchRequestFactory<Q> requestFactory;
    private Function<Hit<T>, ID> idMapper;
    private Function<Hit<T>, Map<String, Object>> attributesMapper = ignored -> Map.of();

    private Builder(ElasticsearchClient client, Class<T> documentType) {
      this.client = Objects.requireNonNull(client, "client must not be null");
      this.documentType = Objects.requireNonNull(documentType, "documentType must not be null");
    }

    /**
     * Supplies the provider-native request factory.
     *
     * @param requestFactory factory for one Elasticsearch search request
     * @return this builder
     */
    public Builder<Q, ID, T> requestFactory(ElasticsearchSearchRequestFactory<Q> requestFactory) {
      this.requestFactory =
          Objects.requireNonNull(requestFactory, "requestFactory must not be null");
      return this;
    }

    /**
     * Maps each native hit into the application's canonical candidate ID.
     *
     * @param idMapper mapping that must return a nonnull canonical ID
     * @return this builder
     */
    public Builder<Q, ID, T> idMapper(Function<Hit<T>, ID> idMapper) {
      this.idMapper = Objects.requireNonNull(idMapper, "idMapper must not be null");
      return this;
    }

    /**
     * Maps each native hit into application-supplied ranking hints.
     *
     * <p>The default returns an empty map. The returned map must have nonnull keys and values and
     * is shallow-copied into the candidate; nested values are not copied. Invalid maps and mapping
     * failures are reported as sanitized {@code INVALID_RESPONSE} failures.
     *
     * @param attributesMapper mapping that must return a nonnull attributes map
     * @return this builder
     */
    public Builder<Q, ID, T> attributesMapper(
        Function<Hit<T>, Map<String, Object>> attributesMapper) {
      this.attributesMapper =
          Objects.requireNonNull(attributesMapper, "attributesMapper must not be null");
      return this;
    }

    /**
     * Validates the request factory and ID mapper.
     *
     * @return configured retriever
     */
    public ElasticsearchCandidateRetriever<Q, ID, T> build() {
      if (requestFactory == null || idMapper == null) {
        throw new IllegalStateException("requestFactory and idMapper are required");
      }
      return new ElasticsearchCandidateRetriever<>(this);
    }
  }
}
