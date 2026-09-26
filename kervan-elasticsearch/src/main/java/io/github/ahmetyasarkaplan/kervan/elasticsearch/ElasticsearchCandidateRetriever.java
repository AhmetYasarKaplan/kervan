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
import java.util.Objects;
import java.util.function.Function;

/** Adapts one Elasticsearch search response into an ordered Kervan provider list. */
public final class ElasticsearchCandidateRetriever<Q, ID, T> implements CandidateRetriever<Q, ID> {

  private final ElasticsearchClient client;
  private final Class<T> documentType;
  private final ElasticsearchSearchRequestFactory<Q> requestFactory;
  private final Function<Hit<T>, ID> idMapper;

  private ElasticsearchCandidateRetriever(Builder<Q, ID, T> builder) {
    client = builder.client;
    documentType = builder.documentType;
    requestFactory = builder.requestFactory;
    idMapper = builder.idMapper;
  }

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
      ID id = idMapper.apply(hit);
      if (id == null) {
        throw new ElasticsearchRetrievalException(
            ElasticsearchRetrievalException.Reason.INVALID_RESPONSE);
      }
      candidates.add(SourceCandidate.of(id, score));
    }
    return List.copyOf(candidates);
  }

  public static final class Builder<Q, ID, T> {
    private final ElasticsearchClient client;
    private final Class<T> documentType;
    private ElasticsearchSearchRequestFactory<Q> requestFactory;
    private Function<Hit<T>, ID> idMapper;

    private Builder(ElasticsearchClient client, Class<T> documentType) {
      this.client = Objects.requireNonNull(client, "client must not be null");
      this.documentType = Objects.requireNonNull(documentType, "documentType must not be null");
    }

    public Builder<Q, ID, T> requestFactory(ElasticsearchSearchRequestFactory<Q> requestFactory) {
      this.requestFactory =
          Objects.requireNonNull(requestFactory, "requestFactory must not be null");
      return this;
    }

    public Builder<Q, ID, T> idMapper(Function<Hit<T>, ID> idMapper) {
      this.idMapper = Objects.requireNonNull(idMapper, "idMapper must not be null");
      return this;
    }

    public ElasticsearchCandidateRetriever<Q, ID, T> build() {
      if (requestFactory == null || idMapper == null) {
        throw new IllegalStateException("requestFactory and idMapper are required");
      }
      return new ElasticsearchCandidateRetriever<>(this);
    }
  }
}
