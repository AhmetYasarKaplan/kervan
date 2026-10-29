package io.github.ahmetyasarkaplan.kervan.opensearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.ScoreDirection;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.core.IdentityRanker;
import io.github.ahmetyasarkaplan.kervan.core.RankingPipeline;
import io.github.ahmetyasarkaplan.kervan.core.RetrieverSpec;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.search_pipeline.ScoreCombinationTechnique;
import org.opensearch.client.opensearch.search_pipeline.ScoreNormalizationTechnique;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

class OpenSearchCandidateRetrieverIntegrationTest {
  private static final String INDEX = "kervan-products";
  private static final GenericContainer<?> CONTAINER =
      new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:3.3.2"))
          .withEnv("discovery.type", "single-node")
          .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
          .withEnv("DISABLE_SECURITY_PLUGIN", "true")
          .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
          .withExposedPorts(9200)
          .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200))
          .withStartupTimeout(Duration.ofMinutes(3));
  private static OpenSearchTransport transport;
  private static OpenSearchClient client;

  @BeforeAll
  static void start() throws IOException {
    CONTAINER.start();
    transport =
        ApacheHttpClient5TransportBuilder.builder(
                new HttpHost("http", CONTAINER.getHost(), CONTAINER.getMappedPort(9200)))
            .build();
    client = new OpenSearchClient(transport);
    client
        .indices()
        .create(
            c ->
                c.index(INDEX)
                    .settings(s -> s.knn(true).numberOfShards(1).numberOfReplicas(0))
                    .mappings(
                        m ->
                            m.properties("canonicalId", p -> p.keyword(k -> k))
                                .properties("title", p -> p.text(t -> t))
                                .properties("embedding", p -> p.knnVector(v -> v.dimension(3)))));
    index("lexical", "camera lens", List.of(0f, 1f, 0f));
    index("vector", "tripod stand", List.of(1f, 0f, 0f));
    index("shared", "camera kit", List.of(0.9f, 0.1f, 0f));
    client.indices().refresh(r -> r.index(INDEX));
  }

  @AfterAll
  static void stop() throws IOException {
    if (transport != null) {
      transport.close();
    }
    CONTAINER.stop();
  }

  @Test
  void shouldRunLexicalAndVectorNativeQueriesWithCanonicalIdsAttributesAndLimits() {
    var lexical = lexical();
    var context = RetrievalContext.of(1, Duration.ofSeconds(5));
    var hits = lexical.retrieve("camera", context);
    assertThat(hits).hasSize(1);
    assertThat(hits.getFirst().id()).startsWith("catalog:");
    assertThat(hits.getFirst().attributes()).containsKey("title");
    var vector =
        OpenSearchCandidateRetriever.<List<Float>, String, Product>builder(client, Product.class)
            .requestFactory(
                (query, ctx) ->
                    new SearchRequest.Builder()
                        .index(INDEX)
                        .query(
                            q ->
                                q.knn(
                                    k ->
                                        k.field("embedding")
                                            .vector(query)
                                            .k(ctx.candidateLimit()))))
            .idMapper(hit -> hit.source().canonicalId())
            .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
            .build();
    var vectorHits =
        vector.retrieve(List.of(1f, 0f, 0f), RetrievalContext.of(2, Duration.ofSeconds(5)));
    assertThat(vectorHits)
        .extracting(SourceCandidate::id)
        .containsExactly("catalog:vector", "catalog:shared");
    assertThat(vectorHits.getFirst().score()).isGreaterThan(vectorHits.getLast().score());
  }

  @Test
  void shouldExecuteNativeHybridQueryWithNormalizationSearchPipeline() throws IOException {
    String searchPipeline = "kervan-hybrid-normalization";
    assertThat(
            client
                .searchPipeline()
                .put(
                    p ->
                        p.id(searchPipeline)
                            .phaseResultsProcessors(
                                processor ->
                                    processor.normalizationProcessor(
                                        normalization ->
                                            normalization
                                                .normalization(
                                                    n ->
                                                        n.technique(
                                                            ScoreNormalizationTechnique.MinMax))
                                                .combination(
                                                    c ->
                                                        c.technique(
                                                                ScoreCombinationTechnique
                                                                    .ArithmeticMean)
                                                            .parameters(
                                                                weights ->
                                                                    weights.weights(0.1f, 0.9f))))))
                .acknowledged())
        .isTrue();
    Function<String, SearchRequest.Builder> nativeRequest =
        query ->
            new SearchRequest.Builder()
                .index(INDEX)
                .searchPipeline(searchPipeline)
                .query(
                    q ->
                        q.hybrid(
                            h ->
                                h.queries(
                                        lexical ->
                                            lexical.match(
                                                m ->
                                                    m.field("title")
                                                        .query(v -> v.stringValue(query))))
                                    .queries(
                                        vector ->
                                            vector.knn(
                                                k ->
                                                    k.field("embedding")
                                                        .vector(List.of(1f, 0f, 0f))
                                                        .k(2)))));
    var hybrid =
        OpenSearchCandidateRetriever.<String, String, Product>builder(client, Product.class)
            .requestFactory((query, context) -> nativeRequest.apply(query))
            .idMapper(hit -> hit.source().canonicalId())
            .attributesMapper(hit -> Map.of("title", hit.source().title()))
            .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
            .build();

    var hits = hybrid.retrieve("camera", RetrievalContext.of(3, Duration.ofSeconds(5)));
    var nativeHits =
        client.search(nativeRequest.apply("camera").size(3).build(), Product.class).hits().hits();

    // The lexical-only document and vector-only document both survive the native hybrid query.
    assertThat(hits)
        .extracting(SourceCandidate::id)
        .containsExactlyInAnyOrder("catalog:lexical", "catalog:vector", "catalog:shared");
    // Preserve the engine's actual normalized ranking, including its native collection semantics.
    assertThat(hits)
        .extracting(SourceCandidate::id)
        .containsExactlyElementsOf(
            nativeHits.stream().map(hit -> hit.source().canonicalId()).toList());
    for (int index = 0; index < hits.size(); index++) {
      assertThat(hits.get(index).score())
          .isCloseTo(nativeHits.get(index).score(), within(0.000001));
    }
    assertThat(hits)
        .allSatisfy(
            hit ->
                assertThat(hit.attributes())
                    .containsEntry(
                        "title",
                        Map.of(
                                "catalog:lexical", "camera lens",
                                "catalog:vector", "tripod stand",
                                "catalog:shared", "camera kit")
                            .get(hit.id())));
  }

  @Test
  void shouldFuseOpenSearchWithAnIndependentProvider() {
    try (var pipeline =
        RankingPipeline.<String, String>builder("opensearch-fusion")
            .addRetriever(RetrieverSpec.required("opensearch", lexical(), 3, Duration.ofSeconds(5)))
            .addRetriever(
                RetrieverSpec.optional(
                    "recommendations",
                    (q, c) ->
                        List.of(
                            SourceCandidate.of("catalog:shared", 1),
                            SourceCandidate.of("catalog:external", 0.5)),
                    2,
                    Duration.ofSeconds(1)))
            .ranker(new IdentityRanker<>(), 4)
            .resultLimit(4)
            .timeout(Duration.ofSeconds(8))
            .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
            .build()) {
      var result = pipeline.execute("camera");
      assertThat(result.isPartial()).isFalse();
      assertThat(result.candidates().getFirst().id()).isEqualTo("catalog:shared");
      assertThat(result.candidates().getFirst().contributions())
          .extracting(c -> c.source())
          .containsExactly("opensearch", "recommendations");
    }
  }

  @Test
  void shouldSanitizeRemoteIndexFailures() {
    var retriever =
        OpenSearchCandidateRetriever.<String, String, Void>builder(client, Void.class)
            .requestFactory(
                (query, context) -> new SearchRequest.Builder().index("private-missing-index"))
            .idMapper(hit -> hit.id())
            .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
            .build();
    assertThatThrownBy(
            () -> retriever.retrieve("secret", RetrievalContext.of(1, Duration.ofSeconds(5))))
        .hasMessage("OpenSearch retrieval failed: REMOTE_FAILURE")
        .hasNoCause();
  }

  private static OpenSearchCandidateRetriever<String, String, Product> lexical() {
    return OpenSearchCandidateRetriever.<String, String, Product>builder(client, Product.class)
        .requestFactory(
            (query, context) ->
                new SearchRequest.Builder()
                    .index(INDEX)
                    .query(q -> q.match(m -> m.field("title").query(v -> v.stringValue(query)))))
        .idMapper(hit -> hit.source().canonicalId())
        .attributesMapper(hit -> Map.of("title", hit.source().title()))
        .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
        .build();
  }

  private static void index(String id, String title, List<Float> vector) throws IOException {
    client.index(
        i ->
            i.index(INDEX)
                .id(id)
                .document(
                    Map.of("canonicalId", "catalog:" + id, "title", title, "embedding", vector)));
  }

  public record Product(String canonicalId, String title, List<Float> embedding) {}
}
