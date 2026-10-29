package io.github.ahmetyasarkaplan.kervan.example;

import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankerFailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.core.IdentityRanker;
import io.github.ahmetyasarkaplan.kervan.core.RankerSpec;
import io.github.ahmetyasarkaplan.kervan.core.RankingPipeline;
import io.github.ahmetyasarkaplan.kervan.core.RetrieverSpec;
import java.time.Duration;
import java.util.List;

/** Runs a deterministic fusion-versus-reranking comparison without external services. */
public final class RerankingExample {
  private RerankingExample() {}

  /**
   * Prints both rankings for a query whose exact product is not first after fusion.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    var query = new ProductQuery("camera tripod", List.of(1.0f, 0.0f, 0.0f));
    try (var baseline = builder("fusion").ranker(new IdentityRanker<>(), 3).build();
        var reranked =
            builder("reranked")
                .ranker(
                    new RankerSpec<>(
                        ProductReranker.titleMatch(),
                        3,
                        3,
                        Duration.ofMillis(300),
                        RankerFailurePolicy.KEEP_FUSED))
                .build()) {
      var fused = baseline.execute(query);
      var result = reranked.execute(query);
      System.out.println("Query: " + query.text());
      System.out.println(
          "Fusion: " + fused.candidates().stream().map(RankedCandidate::id).toList());
      System.out.println(
          "Reranked: " + result.candidates().stream().map(RankedCandidate::id).toList());
      System.out.println(
          "Ranking outcome: "
              + result.rankingOutcome().status()
              + ", degraded="
              + result.isDegraded());
      result
          .candidates()
          .forEach(
              candidate ->
                  System.out.println(
                      candidate.id() + " scores=" + candidate.rerankingScore().orElseThrow()));
    }
  }

  static RankingPipeline.Builder<ProductQuery, String> builder(String name) {
    return RankingPipeline.<ProductQuery, String>builder(name)
        .addRetriever(
            RetrieverSpec.required(
                "search",
                (query, context) ->
                    List.of(
                        SourceCandidate.of("catalog:camera-kit", 9),
                        SourceCandidate.of("catalog:tripod", 8),
                        SourceCandidate.of("catalog:lens", 1)),
                3,
                Duration.ofSeconds(1)))
        .addRetriever(
            RetrieverSpec.optional(
                "recommendations",
                (query, context) ->
                    List.of(
                        SourceCandidate.of("catalog:camera-kit", 0.9),
                        SourceCandidate.of("catalog:lens", 0.8),
                        SourceCandidate.of("catalog:tripod", 0.7)),
                3,
                Duration.ofSeconds(1)))
        .resultLimit(3)
        .timeout(Duration.ofSeconds(2))
        .failurePolicy(FailurePolicy.PARTIAL_RESULTS);
  }
}
