package io.github.ahmetyasarkaplan.kervan.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankerFailurePolicy;
import io.github.ahmetyasarkaplan.kervan.core.IdentityRanker;
import io.github.ahmetyasarkaplan.kervan.core.RankerSpec;
import io.github.ahmetyasarkaplan.kervan.testkit.RankingEvaluation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductRerankingTest {
  @Test
  void batchHydrationAndTitleScoringImproveTheExplicitTripodFixture() {
    try (var baseline =
            RerankingExample.builder("fusion").ranker(new IdentityRanker<>(), 3).build();
        var reranked =
            RerankingExample.builder("reranked")
                .ranker(
                    new RankerSpec<>(
                        ProductReranker.titleMatch(),
                        3,
                        3,
                        Duration.ofMillis(500),
                        RankerFailurePolicy.KEEP_FUSED))
                .build()) {
      var query = new ProductQuery("camera tripod", List.of(1.0f, 0.0f, 0.0f));
      var original = baseline.execute(query);
      var result = reranked.execute(query);
      var baselineIds = original.candidates().stream().map(RankedCandidate::id).toList();
      var rerankedIds = result.candidates().stream().map(RankedCandidate::id).toList();
      assertThat(baselineIds)
          .containsExactly("catalog:camera-kit", "catalog:tripod", "catalog:lens");
      assertThat(rerankedIds)
          .containsExactly("catalog:tripod", "catalog:camera-kit", "catalog:lens");
      var judgments = Map.of("catalog:tripod", 3, "catalog:camera-kit", 1, "catalog:lens", 0);
      assertThat(RankingEvaluation.evaluate(rerankedIds, judgments, 3).ndcgAtK())
          .isEqualTo(1.0)
          .isGreaterThan(RankingEvaluation.evaluate(baselineIds, judgments, 3).ndcgAtK());
      assertThat(result.isDegraded()).isFalse();
      for (var candidate : result.candidates()) {
        var before =
            original.candidates().stream()
                .filter(value -> value.id().equals(candidate.id()))
                .findFirst()
                .orElseThrow();
        assertThat(candidate.rerankingScore().orElseThrow().fusionScore())
            .isEqualTo(before.score());
        assertThat(candidate.contributions()).isEqualTo(before.contributions());
      }
    }
  }
}
