package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.FusionContext;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalResult;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import io.github.ahmetyasarkaplan.kervan.api.SourceHit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeightedMinMaxFusionTest {

  @Test
  void shouldCalculateWeightedScoresFromEachRetrievedWindow() {
    var search = result("search", hit("a", 10, 1), hit("shared", 5, 2), hit("c", 0, 3));
    var recommendations =
        result("recommendations", hit("shared", 100, 1), hit("b", 50, 2), hit("c", 0, 3));
    var context = FusionContext.of(4, Map.of("search", 1.0, "recommendations", 2.0));

    var fused = new WeightedMinMaxFusion<String>().fuse(List.of(search, recommendations), context);

    assertThat(fused).extracting(RankedCandidate::id).containsExactly("shared", "a", "b", "c");
    assertThat(fused).extracting(RankedCandidate::score).containsExactly(2.5, 1.0, 1.0, 0.0);
    assertThat(fused.getFirst().contributions())
        .containsExactly(
            new SourceContribution("search", 2, 5.0),
            new SourceContribution("recommendations", 1, 100.0));
    assertThatThrownBy(fused::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldGiveEqualScoreWindowsFullPresenceCreditAndDefaultMissingWeightToOne() {
    var search = result("search", hit("a", 5, 1), hit("b", 5, 2));
    var fusion = new WeightedMinMaxFusion<String>();

    var defaultWeight = fusion.fuse(List.of(search), FusionContext.of(2));
    var customWeight = fusion.fuse(List.of(search), FusionContext.of(2, Map.of("search", 2.0)));

    assertThat(defaultWeight).extracting(RankedCandidate::score).containsExactly(1.0, 1.0);
    assertThat(customWeight).extracting(RankedCandidate::score).containsExactly(2.0, 2.0);
    assertThat(customWeight).extracting(RankedCandidate::id).containsExactly("a", "b");
  }

  @Test
  void shouldNormalizeNegativeAndExtremeFiniteScoresWithoutOverflow() {
    var negative = result("negative", hit("low", -10, 1), hit("mid", 0, 2), hit("high", 10, 3));
    var extreme =
        result(
            "extreme",
            hit("low", -Double.MAX_VALUE, 1),
            hit("mid", 0, 2),
            hit("high", Double.MAX_VALUE, 3));
    var fusion = new WeightedMinMaxFusion<String>();

    for (var result : List.of(negative, extreme)) {
      var fused = fusion.fuse(List.of(result), FusionContext.of(3));
      assertThat(fused).extracting(RankedCandidate::id).containsExactly("high", "mid", "low");
      assertThat(fused).extracting(RankedCandidate::score).containsExactly(1.0, 0.5, 0.0);
    }
  }

  @Test
  void shouldIgnoreLaterDuplicateWithinSourceBeforeComputingScoreRange() {
    var search = result("search", hit("a", 1, 1), hit("a", 100, 2), hit("b", 3, 3));

    var fused = new WeightedMinMaxFusion<String>().fuse(List.of(search), FusionContext.of(2));

    assertThat(fused).extracting(RankedCandidate::id).containsExactly("b", "a");
    assertThat(fused).extracting(RankedCandidate::score).containsExactly(1.0, 0.0);
    assertThat(fused.getLast().contributions())
        .containsExactly(new SourceContribution("search", 1, 1.0));
  }

  @Test
  void shouldLimitOutputAfterMergingAndIgnoreEmptyAndFailedSources() {
    var search = result("search", hit("a", 2, 1), hit("b", 1, 2));
    var failed = RetrievalResult.<String>failure("failed", Duration.ZERO);
    var empty = RetrievalResult.<String>empty("empty", Duration.ZERO);

    var fused =
        new WeightedMinMaxFusion<String>()
            .fuse(
                List.of(failed, search, empty),
                FusionContext.of(1, Map.of("failed", 3.0, "empty", 2.0)));

    assertThat(fused).extracting(RankedCandidate::id).containsExactly("a");
    assertThat(fused.getFirst().score()).isEqualTo(1.0);
  }

  @Test
  void shouldRejectUnknownWeightAndOverflowingFusedScore() {
    var search = result("search", hit("a", 1, 1));
    var recommendations = result("recommendations", hit("a", 2, 1));
    var fusion = new WeightedMinMaxFusion<String>();

    assertThatThrownBy(() -> fusion.fuse(List.of(search), FusionContext.of(1, Map.of("typo", 2.0))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown source");
    assertThatThrownBy(
            () ->
                fusion.fuse(
                    List.of(search, recommendations),
                    FusionContext.of(
                        1,
                        Map.of("search", Double.MAX_VALUE, "recommendations", Double.MAX_VALUE))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("finite");
  }

  @Test
  void shouldRejectNullInputsAndReturnEmptyForOnlyEmptySources() {
    var fusion = new WeightedMinMaxFusion<String>();
    assertThatThrownBy(() -> fusion.fuse(null, FusionContext.of(1)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> fusion.fuse(List.of(), null)).isInstanceOf(NullPointerException.class);
    assertThat(
            fusion.fuse(
                List.of(RetrievalResult.empty("empty", Duration.ZERO)), FusionContext.of(1)))
        .isEmpty();
  }

  private static SourceHit<String> hit(String id, double score, int rank) {
    return new SourceHit<>(SourceCandidate.of(id, score), rank);
  }

  @SafeVarargs
  private static RetrievalResult<String> result(String source, SourceHit<String>... hits) {
    var candidates = new ArrayList<SourceHit<String>>();
    for (SourceHit<String> hit : hits) {
      candidates.add(hit);
    }
    return RetrievalResult.success(source, candidates, Duration.ZERO);
  }
}
