package io.github.ahmetyasarkaplan.kervan.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalStatus;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.core.IdentityRanker;
import io.github.ahmetyasarkaplan.kervan.core.RankingPipeline;
import io.github.ahmetyasarkaplan.kervan.core.RetrieverSpec;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

class ElasticsearchCandidateRetrieverIntegrationTest {

  private static final String INDEX = "kervan-products";
  private static final ElasticsearchContainer CONTAINER =
      new ElasticsearchContainer(
              DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.5.0"))
          .withEnv("xpack.security.enabled", "false")
          .withEnv("xpack.security.http.ssl.enabled", "false")
          .withEnv("xpack.security.transport.ssl.enabled", "false")
          .withStartupTimeout(Duration.ofMinutes(3));

  private static ElasticsearchClient client;

  @BeforeAll
  static void startElasticsearch() throws IOException {
    CONTAINER.start();
    client = ElasticsearchClient.of(b -> b.host("http://" + CONTAINER.getHttpHostAddress()));
    client
        .indices()
        .create(
            c ->
                c.index(INDEX)
                    .withJson(
                        new java.io.StringReader(
                            """
                            {
                              "mappings": {
                                "properties": {
                                  "title": {"type": "text"},
                                  "vector": {
                                    "type": "dense_vector",
                                    "dims": 3,
                                    "index": true,
                                    "similarity": "cosine"
                                  }
                                }
                              }
                            }
                            """)));
    index("lexical", "camera lens", List.of(0.0, 1.0, 0.0));
    index("vector", "tripod stand", List.of(1.0, 0.0, 0.0));
    index("shared", "camera kit", List.of(0.9, 0.1, 0.0));
    client.indices().refresh(r -> r.index(INDEX));
  }

  @AfterAll
  static void stopElasticsearch() throws IOException {
    if (client != null) {
      client.close();
    }
    CONTAINER.stop();
  }

  @Test
  void shouldApplyCandidateLimitAndMapDocumentIdentityToCanonicalIds() {
    var retriever =
        ElasticsearchCandidateRetriever.<String, String, ProductDocument>builder(
                client, ProductDocument.class)
            .requestFactory(
                (query, context) ->
                    new SearchRequest.Builder()
                        .index(INDEX)
                        .query(q -> q.match(m -> m.field("title").query(query))))
            .idMapper(hit -> hit.source().canonicalId())
            .build();

    var results = retriever.retrieve("camera", RetrievalContext.of(1, Duration.ofSeconds(2)));

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().id()).startsWith("catalog:");
    assertThat(results.getFirst().score()).isFinite();
  }

  @Test
  void shouldFuseOneNativeHybridResultWithAnIndependentProvider() {
    var elasticsearch = hybridRetriever(client);
    CandidateRetriever<HybridQuery, String> recommendations =
        (query, context) ->
            List.of(
                SourceCandidate.of("catalog:vector", 0.9),
                SourceCandidate.of("recommendations:only", 0.8));

    try (var pipeline =
        RankingPipeline.<HybridQuery, String>builder("products")
            .addRetriever(
                RetrieverSpec.required("elasticsearch", elasticsearch, 3, Duration.ofSeconds(5)))
            .addRetriever(
                RetrieverSpec.optional(
                    "recommendations", recommendations, 2, Duration.ofSeconds(1)))
            .ranker(new IdentityRanker<>(), 5)
            .resultLimit(5)
            .timeout(Duration.ofSeconds(10))
            .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
            .build()) {
      var result = pipeline.execute(new HybridQuery("camera", List.of(1.0f, 0.0f, 0.0f)));

      assertThat(result.retrievalResults())
          .extracting(retrieval -> retrieval.status())
          .containsExactly(RetrievalStatus.SUCCESS, RetrievalStatus.SUCCESS);
      assertThat(result.retrievalResults().getFirst().candidates())
          .extracting(hit -> hit.candidate().id())
          .contains("catalog:lexical", "catalog:vector");
      assertThat(result.candidates())
          .extracting(RankedCandidate::id)
          .contains("catalog:vector", "recommendations:only");
      assertThat(
              result.candidates().stream()
                  .filter(candidate -> candidate.id().equals("catalog:vector"))
                  .findFirst()
                  .orElseThrow()
                  .contributions())
          .extracting(contribution -> contribution.source())
          .containsExactly("elasticsearch", "recommendations");
    }
  }

  @Test
  void shouldPromoteALowerRankedNativeHybridHitIntoTheFinalTopK() {
    var elasticsearch = hybridRetriever(client);
    var query = new HybridQuery("camera", List.of(1.0f, 0.0f, 0.0f));
    var nativeHits = elasticsearch.retrieve(query, RetrievalContext.of(3, Duration.ofSeconds(5)));
    assertThat(nativeHits).hasSizeGreaterThanOrEqualTo(2);

    String firstNativeId = nativeHits.getFirst().id();
    String lastNativeId = nativeHits.getLast().id();
    assertThat(lastNativeId).isNotEqualTo(firstNativeId);
    CandidateRetriever<HybridQuery, String> recommendations =
        (ignored, context) -> List.of(SourceCandidate.of(lastNativeId, 1.0));

    try (var pipeline =
        RankingPipeline.<HybridQuery, String>builder("hybrid-top-k")
            .addRetriever(
                RetrieverSpec.required("elasticsearch", elasticsearch, 3, Duration.ofSeconds(5)))
            .addRetriever(
                RetrieverSpec.optional(
                    "recommendations", recommendations, 1, Duration.ofSeconds(1)))
            .ranker(new IdentityRanker<>(), 3)
            .resultLimit(2)
            .timeout(Duration.ofSeconds(10))
            .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
            .build()) {
      var result = pipeline.execute(query);

      assertThat(result.isPartial()).isFalse();
      assertThat(result.retrievalResults().getFirst().candidates())
          .extracting(hit -> hit.candidate().id())
          .containsExactlyElementsOf(nativeHits.stream().map(SourceCandidate::id).toList());
      assertThat(result.candidates())
          .extracting(RankedCandidate::id)
          .containsExactly(lastNativeId, firstNativeId);
      assertThat(result.candidates().getFirst().contributions())
          .extracting(contribution -> contribution.source())
          .containsExactly("elasticsearch", "recommendations");
    }
  }

  @Test
  void shouldSanitizeRemoteServerErrors() {
    var retriever =
        ElasticsearchCandidateRetriever.<String, String, Void>builder(client, Void.class)
            .requestFactory(
                (query, context) ->
                    new SearchRequest.Builder()
                        .index("private-missing-index")
                        .query(q -> q.matchAll(m -> m))
                        .source(s -> s.fetch(false)))
            .idMapper(hit -> hit.id())
            .build();

    assertThatThrownBy(
            () -> retriever.retrieve("ignored", RetrievalContext.of(1, Duration.ofSeconds(2))))
        .isInstanceOfSatisfying(
            ElasticsearchRetrievalException.class,
            failure ->
                assertThat(failure.reason())
                    .isEqualTo(ElasticsearchRetrievalException.Reason.REMOTE_FAILURE))
        .hasMessage("Elasticsearch retrieval failed: REMOTE_FAILURE")
        .hasNoCause();
  }

  @Test
  void shouldSanitizeUnavailableServerFailureAndAllowPartialPipelineResult() throws IOException {
    try (var unavailable = ElasticsearchClient.of(b -> b.host("http://127.0.0.1:1"))) {
      var elasticsearch = hybridRetriever(unavailable);
      var query = new HybridQuery("camera", List.of(1.0f, 0.0f, 0.0f));

      assertThatThrownBy(
              () -> elasticsearch.retrieve(query, RetrievalContext.of(2, Duration.ofSeconds(1))))
          .isInstanceOf(ElasticsearchRetrievalException.class)
          .hasMessage("Elasticsearch retrieval failed: TRANSPORT_FAILURE")
          .hasNoCause();

      CandidateRetriever<HybridQuery, String> fallback =
          (ignored, context) -> List.of(SourceCandidate.of("fallback", 0.5));
      try (var pipeline =
          RankingPipeline.<HybridQuery, String>builder("fallback")
              .addRetriever(
                  RetrieverSpec.optional("elasticsearch", elasticsearch, 2, Duration.ofSeconds(3)))
              .addRetriever(RetrieverSpec.required("fallback", fallback, 1, Duration.ofSeconds(1)))
              .ranker(new IdentityRanker<>(), 3)
              .resultLimit(3)
              .timeout(Duration.ofSeconds(5))
              .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
              .build()) {
        var result = pipeline.execute(query);
        assertThat(result.isPartial()).isTrue();
        assertThat(result.retrievalResults())
            .extracting(retrieval -> retrieval.status())
            .containsExactly(RetrievalStatus.FAILED, RetrievalStatus.SUCCESS);
        assertThat(result.candidates()).extracting(RankedCandidate::id).containsExactly("fallback");
      }
    }
  }

  private static void index(String id, String title, List<Double> vector) throws IOException {
    client.index(
        i ->
            i.index(INDEX)
                .id(id)
                .document(
                    Map.of(
                        "canonicalId", "catalog:" + id,
                        "title", title,
                        "vector", vector)));
  }

  private static ElasticsearchCandidateRetriever<HybridQuery, String, Void> hybridRetriever(
      ElasticsearchClient searchClient) {
    return ElasticsearchCandidateRetriever.<HybridQuery, String, Void>builder(
            searchClient, Void.class)
        .requestFactory(
            (query, context) ->
                new SearchRequest.Builder()
                    .index(INDEX)
                    .query(q -> q.match(m -> m.field("title").query(query.text())))
                    .knn(
                        k ->
                            k.field("vector")
                                .queryVector(query.vector())
                                .k(context.candidateLimit())
                                .numCandidates(10))
                    .source(s -> s.fetch(false)))
        .idMapper(hit -> "catalog:" + hit.id())
        .build();
  }

  private record HybridQuery(String text, List<Float> vector) {}

  public record ProductDocument(String canonicalId, String title, List<Double> vector) {}
}
