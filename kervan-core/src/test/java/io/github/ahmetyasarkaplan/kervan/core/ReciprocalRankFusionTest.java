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

class ReciprocalRankFusionTest {

  @Test
  void shouldMatchHandCalculatedScoresAndPreserveProvenance() {
    var search = result("search", hit("a", 8.0, 1), hit("shared", 7.0, 2));
    var recommendations = result("recommendations", hit("shared", 0.8, 1), hit("b", 0.7, 2));

    var fused =
        new ReciprocalRankFusion<String>(2)
            .fuse(
                List.of(search, recommendations),
                FusionContext.of(10, Map.of("search", 100.0, "recommendations", 0.1)));

    assertThat(fused).extracting(RankedCandidate::id).containsExactly("shared", "a", "b");
    assertThat(fused.get(0).score()).isEqualTo(1.0 / 4 + 1.0 / 3);
    assertThat(fused.get(1).score()).isEqualTo(1.0 / 3);
    assertThat(fused.get(2).score()).isEqualTo(1.0 / 4);
    assertThat(fused.get(0).contributions())
        .containsExactly(
            new SourceContribution("search", 2, 7.0),
            new SourceContribution("recommendations", 1, 0.8));
    assertThatThrownBy(fused::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldBreakTiesByProviderOrderWithoutComparingGenericIds() {
    var first = new OpaqueId(1);
    var second = new OpaqueId(2);
    var search = result("search", hit(first, 10.0, 1));
    var recommendations = result("recommendations", hit(second, 1.0, 1));
    var fusion = new ReciprocalRankFusion<OpaqueId>(60);

    for (int run = 0; run < 100; run++) {
      var fused = fusion.fuse(List.of(search, recommendations), FusionContext.of(2));
      assertThat(fused).extracting(RankedCandidate::id).containsExactly(first, second);
      assertThat(fused.getFirst().score()).isEqualTo(fused.getLast().score());
    }
  }

  @Test
  void shouldCountOnlyFirstOccurrencePerSourceAndLimitAfterCrossSourceMerge() {
    var search = result("search", hit("a", 1.0, 1), hit("a", 99.0, 2), hit("b", 2.0, 3));
    var recommendations = result("recommendations", hit("b", 0.9, 1));

    var fused =
        new ReciprocalRankFusion<String>(1)
            .fuse(List.of(search, recommendations), FusionContext.of(1));

    assertThat(fused).extracting(RankedCandidate::id).containsExactly("b");
    assertThat(fused.getFirst().score()).isEqualTo(1.0 / 4 + 1.0 / 2);
    assertThat(fused.getFirst().contributions())
        .containsExactly(
            new SourceContribution("search", 3, 2.0),
            new SourceContribution("recommendations", 1, 0.9));
  }

  @Test
  void shouldIgnoreFailedAndEmptyProvidersAndUseAttributesFromFirstSource() {
    var search =
        RetrievalResult.success(
            "search",
            List.of(
                new SourceHit<>(new SourceCandidate<>("a", 2.0, Map.of("category", "books")), 1)),
            Duration.ZERO);
    var failed = RetrievalResult.<String>failure("failed", Duration.ZERO);
    var empty = RetrievalResult.<String>empty("empty", Duration.ZERO);
    var fused =
        new ReciprocalRankFusion<String>(60)
            .fuse(List.of(failed, search, empty), FusionContext.of(5));

    assertThat(fused).hasSize(1);
    assertThat(fused.getFirst().attributes()).containsEntry("category", "books");
    assertThat(fused.getFirst().contributions())
        .containsExactly(new SourceContribution("search", 1, 2.0, Map.of("category", "books")));
  }

  @Test
  void shouldValidateRankConstantAndAcceptLargeValidRankWithoutIntegerOverflow() {
    assertThatThrownBy(() -> new ReciprocalRankFusion<String>(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rankConstant");
    assertThatThrownBy(() -> new ReciprocalRankFusion<String>(-1))
        .isInstanceOf(IllegalArgumentException.class);

    var result = result("search", hit("a", 1.0, Integer.MAX_VALUE));
    var fused =
        new ReciprocalRankFusion<String>(Integer.MAX_VALUE)
            .fuse(List.of(result), FusionContext.of(1));
    assertThat(fused.getFirst().score()).isPositive().isFinite();
  }

  @Test
  void shouldRejectNullInputsAndDuplicateSourceNames() {
    var fusion = new ReciprocalRankFusion<String>(60);
    assertThatThrownBy(() -> fusion.fuse(null, FusionContext.of(1)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> fusion.fuse(List.of(), null)).isInstanceOf(NullPointerException.class);
    var first = result("search", hit("a", 1.0, 1));
    var second = result("search", hit("b", 1.0, 1));
    assertThatThrownBy(() -> fusion.fuse(List.of(first, second), FusionContext.of(2)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique source names");
  }

  private record OpaqueId(int value) {
    @Override
    public String toString() {
      throw new AssertionError("candidate IDs must not be stringified for ordering");
    }
  }

  private static <ID> SourceHit<ID> hit(ID id, double score, int rank) {
    return new SourceHit<>(SourceCandidate.of(id, score), rank);
  }

  @SafeVarargs
  private static <ID> RetrievalResult<ID> result(String source, SourceHit<ID>... hits) {
    var candidates = new ArrayList<SourceHit<ID>>();
    for (SourceHit<ID> hit : hits) {
      candidates.add(hit);
    }
    return RetrievalResult.success(source, candidates, Duration.ZERO);
  }
}
