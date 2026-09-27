package io.github.ahmetyasarkaplan.kervan.qdrant;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalStatus;
import io.github.ahmetyasarkaplan.kervan.api.ScoreDirection;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.core.IdentityRanker;
import io.github.ahmetyasarkaplan.kervan.core.RankingPipeline;
import io.github.ahmetyasarkaplan.kervan.core.RetrieverSpec;
import io.github.ahmetyasarkaplan.kervan.core.WeightedMinMaxFusion;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.qdrant.QdrantContainer;

class QdrantCandidateRetrieverIntegrationTest {

  private static final String COSINE_COLLECTION = "kervan_cosine";
  private static final String EUCLID_COLLECTION = "kervan_euclid";
  private static final QdrantContainer CONTAINER =
      new QdrantContainer("qdrant/qdrant:v1.18.1").withStartupTimeout(Duration.ofMinutes(2));

  private static QdrantClient client;

  @BeforeAll
  static void startQdrant() throws Exception {
    CONTAINER.start();
    client =
        new QdrantClient(
            QdrantGrpcClient.newBuilder(CONTAINER.getHost(), CONTAINER.getMappedPort(6334), false)
                .build());
    createCollection(COSINE_COLLECTION, Distance.Cosine);
    createCollection(EUCLID_COLLECTION, Distance.Euclid);
    client
        .upsertAsync(
            COSINE_COLLECTION,
            List.of(
                point(1, "catalog:camera", 1.0f, 0.0f, 0.0f),
                point(2, "catalog:tripod", 0.0f, 1.0f, 0.0f)))
        .get();
    client
        .upsertAsync(
            EUCLID_COLLECTION,
            List.of(
                point(3, "catalog:near", 0.1f, 0.0f, 0.0f),
                point(4, "catalog:far", 0.9f, 0.0f, 0.0f)))
        .get();
  }

  @AfterAll
  static void stopQdrant() {
    if (client != null) {
      client.close();
    }
    CONTAINER.stop();
  }

  @Test
  void shouldMapCanonicalIdsAndBoundNativeResultSize() {
    var retriever = retriever(COSINE_COLLECTION, ScoreDirection.HIGHER_IS_BETTER);

    var results =
        retriever.retrieve(
            List.of(1.0f, 0.0f, 0.0f), RetrievalContext.of(1, Duration.ofSeconds(2)));

    assertThat(results).extracting(SourceCandidate::id).containsExactly("catalog:camera");
    assertThat(results.getFirst().score()).isPositive();
    assertThat(results.getFirst().attributes()).containsEntry("nativeId", 1L);
  }

  @Test
  void shouldFuseQdrantWithAnIndependentRecommendationSource() {
    var qdrant = retriever(COSINE_COLLECTION, ScoreDirection.HIGHER_IS_BETTER);
    CandidateRetriever<List<Float>, String> recommendations =
        (query, context) ->
            List.of(
                SourceCandidate.of("catalog:tripod", 0.9),
                SourceCandidate.of("catalog:recommendation-only", 0.8));

    try (var pipeline =
        RankingPipeline.<List<Float>, String>builder("discovery")
            .addRetriever(RetrieverSpec.required("qdrant", qdrant, 2, Duration.ofSeconds(3)))
            .addRetriever(
                RetrieverSpec.optional(
                    "recommendations", recommendations, 2, Duration.ofSeconds(1)))
            .ranker(new IdentityRanker<>(), 3)
            .resultLimit(3)
            .timeout(Duration.ofSeconds(5))
            .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
            .build()) {
      var result = pipeline.execute(List.of(1.0f, 0.0f, 0.0f));
      assertThat(result.retrievalResults())
          .extracting(retrieval -> retrieval.status())
          .containsExactly(RetrievalStatus.SUCCESS, RetrievalStatus.SUCCESS);
      assertThat(result.candidates()).extracting(RankedCandidate::id).contains("catalog:tripod");
      assertThat(
              result.candidates().stream()
                  .filter(candidate -> candidate.id().equals("catalog:tripod"))
                  .findFirst()
                  .orElseThrow()
                  .contributions())
          .extracting(contribution -> contribution.source())
          .containsExactly("qdrant", "recommendations");
    }
  }

  @Test
  void shouldConvertEuclideanDistanceBeforeScoreFusion() {
    var retriever = retriever(EUCLID_COLLECTION, ScoreDirection.LOWER_IS_BETTER);
    var results =
        retriever.retrieve(
            List.of(0.0f, 0.0f, 0.0f), RetrievalContext.of(2, Duration.ofSeconds(2)));

    assertThat(results)
        .extracting(SourceCandidate::id)
        .containsExactly("catalog:near", "catalog:far");
    assertThat(results.getFirst().score()).isGreaterThan(results.getLast().score());
    try (var pipeline =
        RankingPipeline.<List<Float>, String>builder("distance")
            .addRetriever(RetrieverSpec.required("qdrant", retriever, 2, Duration.ofSeconds(2)))
            .fusion(new WeightedMinMaxFusion<>(), 2)
            .ranker(new IdentityRanker<>(), 2)
            .resultLimit(2)
            .timeout(Duration.ofSeconds(4))
            .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
            .build()) {
      assertThat(pipeline.execute(List.of(0.0f, 0.0f, 0.0f)).candidates())
          .extracting(RankedCandidate::id)
          .containsExactly("catalog:near", "catalog:far");
    }
  }

  @Test
  void shouldSanitizeRemoteErrors() {
    var retriever = retriever("private_missing_collection", ScoreDirection.HIGHER_IS_BETTER);

    assertThatThrownBy(
            () ->
                retriever.retrieve(
                    List.of(1.0f, 0.0f, 0.0f), RetrievalContext.of(1, Duration.ofSeconds(2))))
        .isInstanceOfSatisfying(
            QdrantRetrievalException.class,
            failure ->
                assertThat(failure.reason())
                    .isEqualTo(QdrantRetrievalException.Reason.REMOTE_FAILURE))
        .hasMessage("Qdrant retrieval failed: REMOTE_FAILURE")
        .hasNoCause();
  }

  private static void createCollection(String name, Distance distance)
      throws InterruptedException, ExecutionException {
    client
        .createCollectionAsync(
            name, VectorParams.newBuilder().setSize(3).setDistance(distance).build())
        .get();
  }

  private static PointStruct point(long id, String canonicalId, float x, float y, float z) {
    return PointStruct.newBuilder()
        .setId(id(id))
        .setVectors(vectors(x, y, z))
        .putAllPayload(Map.of("canonicalId", value(canonicalId)))
        .build();
  }

  private static QdrantCandidateRetriever<List<Float>, String> retriever(
      String collection, ScoreDirection direction) {
    return QdrantCandidateRetriever.<List<Float>, String>builder(client)
        .requestFactory(
            (vector, context) ->
                QueryPoints.newBuilder()
                    .setCollectionName(collection)
                    .setQuery(nearest(vector))
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true)))
        .idMapper(point -> point.getPayloadOrThrow("canonicalId").getStringValue())
        .attributesMapper(point -> Map.of("nativeId", point.getId().getNum()))
        .scoreDirection(direction)
        .build();
  }
}
