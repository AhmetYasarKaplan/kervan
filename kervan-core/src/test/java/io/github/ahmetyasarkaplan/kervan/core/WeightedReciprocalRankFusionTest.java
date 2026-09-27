package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.FusionContext;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalResult;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.api.SourceHit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeightedReciprocalRankFusionTest {

  @Test
  void shouldPromoteTheStrongerSourceWithoutComparingUnrelatedScores() {
    var search = result("search", "search-only", 1000.0);
    var recommendations = result("recommendations", "recommendation-only", 0.01);
    var weights = Map.of("search", 1.0, "recommendations", 3.0);

    var fused =
        new WeightedReciprocalRankFusion<String>(2)
            .fuse(List.of(search, recommendations), FusionContext.of(2, weights));

    assertThat(fused)
        .extracting(RankedCandidate::id)
        .containsExactly("recommendation-only", "search-only");
    assertThat(fused.getFirst().score()).isEqualTo(3.0 / 3);
    assertThat(fused.getLast().score()).isEqualTo(1.0 / 3);
  }

  @Test
  void shouldMatchUnweightedRrfWhenEverySourceUsesTheDefaultWeight() {
    var search = result("search", "shared", 9.0);
    var recommendations = result("recommendations", "shared", 0.2);
    var results = List.of(search, recommendations);

    var weighted = new WeightedReciprocalRankFusion<String>(60).fuse(results, FusionContext.of(3));
    var standard = new ReciprocalRankFusion<String>(60).fuse(results, FusionContext.of(3));

    assertThat(weighted).isEqualTo(standard);
    assertThat(weighted.getFirst().contributions()).hasSize(2);
  }

  @Test
  void shouldRejectWeightsForUnknownSourcesAndOverflowedTotals() {
    var search = result("search", "shared", 1.0);
    var recommendations = result("recommendations", "shared", 1.0);
    var popularity = result("popularity", "shared", 1.0);
    var fusion = new WeightedReciprocalRankFusion<String>(1);

    assertThatThrownBy(
            () -> fusion.fuse(List.of(search), FusionContext.of(1, Map.of("unregistered", 2.0))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown source");
    assertThatThrownBy(
            () ->
                fusion.fuse(
                    List.of(search, recommendations, popularity),
                    FusionContext.of(
                        1,
                        Map.of(
                            "search", Double.MAX_VALUE,
                            "recommendations", Double.MAX_VALUE,
                            "popularity", Double.MAX_VALUE))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("finite");
  }

  private static RetrievalResult<String> result(String source, String id, double score) {
    return RetrievalResult.success(
        source, List.of(new SourceHit<>(SourceCandidate.of(id, score), 1)), Duration.ZERO);
  }
}
