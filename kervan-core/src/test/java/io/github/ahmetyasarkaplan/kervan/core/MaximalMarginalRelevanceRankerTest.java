package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import io.github.ahmetyasarkaplan.kervan.api.RerankingScore;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleBiFunction;
import org.junit.jupiter.api.Test;

class MaximalMarginalRelevanceRankerTest {
  @Test
  void shouldPreferDistinctCandidateAndRetainMaximumSimilarityAcrossSelections() {
    var ranker =
        new MaximalMarginalRelevanceRanker<String, String>(
            0.5,
            4,
            RankedCandidate::score,
            (candidate, selected) ->
                switch (candidate.id() + selected.id()) {
                  case "ba" -> 0.95;
                  case "ca", "bc" -> 0.1;
                  case "da" -> 0.2;
                  case "dc" -> 0.9;
                  default -> 0.0;
                });

    var output =
        ranker.rank(
            null,
            List.of(ranked("a", 1.0), ranked("b", 0.95), ranked("c", 0.8), ranked("d", 0.7)),
            RankingContext.of(3));

    // After a, utilities are b=0, c=.35, d=.25. After c, b=0 and d=-.1.
    assertThat(output).extracting(RankedCandidate::id).containsExactly("a", "c", "b");
  }

  @Test
  void shouldSupportLambdaEndpointsWithStableTiesAndOpaqueIds() {
    var first = ranked(new OpaqueId(1), 0.2);
    var second = ranked(new OpaqueId(2), 0.9);
    var third = ranked(new OpaqueId(3), 0.9);
    var input = List.of(first, second, third);
    var relevanceCalls = new AtomicInteger();
    var novelty =
        new MaximalMarginalRelevanceRanker<String, OpaqueId>(
            0.0,
            3,
            candidate -> {
              relevanceCalls.incrementAndGet();
              return candidate.score();
            },
            (candidate, selected) -> candidate == second ? 0.8 : 0.1);
    assertThat(novelty.rank(null, input, RankingContext.of(3)))
        .containsExactly(first, third, second);
    assertThat(relevanceCalls).hasValue(3);

    var relevanceOnly =
        new MaximalMarginalRelevanceRanker<String, OpaqueId>(
            1.0,
            3,
            RankedCandidate::score,
            (candidate, selected) -> {
              throw new AssertionError("lambda one must skip unused similarity");
            });
    assertThat(relevanceOnly.rank(null, input, RankingContext.of(3)))
        .containsExactly(second, third, first);
  }

  @Test
  void shouldPreserveOriginalObjectsAllScoresProvenanceAndImmutableOutput() {
    var original =
        new RankedCandidate<>(
            "a",
            42.0,
            List.of(new SourceContribution("search", 2, 9.0, Map.of("title", "source title"))),
            Map.of("title", "winner title", "typed", List.of(1, 2)),
            Optional.of(new RerankingScore(0.2, 42.0)));
    var later = ranked("b", 0.1);
    var input = new ArrayList<>(List.of(original, later));
    var ranker =
        new MaximalMarginalRelevanceRanker<String, String>(
            0.5,
            3,
            candidate -> {
              input.clear();
              return candidate == original ? 1.0 : 0.0;
            },
            (candidate, selected) -> 0.0);

    var output = ranker.rank(null, input, RankingContext.of(2));

    assertThat(output.getFirst()).isSameAs(original);
    assertThat(output.getLast()).isSameAs(later);
    assertThat(original.score()).isEqualTo(42.0);
    assertThat(original.rerankingScore()).contains(new RerankingScore(0.2, 42.0));
    assertThat(original.contributions().getFirst().attributes())
        .containsEntry("title", "source title");
    assertThat(original.attributes()).containsEntry("typed", List.of(1, 2));
    assertThatThrownBy(output::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldEvaluateOnlyNewPairsAndNoPairsAfterReachingOutputLimit() {
    var relevanceCalls = new AtomicInteger();
    var pairs = new HashSet<List<String>>();
    var ranker =
        new MaximalMarginalRelevanceRanker<String, String>(
            0.5,
            4,
            candidate -> {
              relevanceCalls.incrementAndGet();
              return candidate.score();
            },
            (candidate, selected) -> {
              assertThat(pairs.add(List.of(candidate.id(), selected.id()))).isTrue();
              return 0.0;
            });

    assertThat(
            ranker.rank(
                null,
                List.of(ranked("a", 0.9), ranked("b", 0.8), ranked("c", 0.7), ranked("d", 0.6)),
                RankingContext.of(3)))
        .extracting(RankedCandidate::id)
        .containsExactly("a", "b", "c");
    assertThat(relevanceCalls).hasValue(4);
    assertThat(pairs)
        .containsExactlyInAnyOrder(
            List.of("b", "a"),
            List.of("c", "a"),
            List.of("d", "a"),
            List.of("c", "b"),
            List.of("d", "b"));
  }

  @Test
  void shouldRejectInvalidConfigurationInputsAndDuplicateIdsBeforeCallbacks() {
    for (double value : List.of(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY)) {
      assertThatThrownBy(
              () ->
                  new MaximalMarginalRelevanceRanker<String, String>(
                      value, 3, RankedCandidate::score, (candidate, selected) -> 0.0))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (int bound : List.of(0, -1)) {
      assertThatThrownBy(
              () ->
                  new MaximalMarginalRelevanceRanker<String, String>(
                      0.5, bound, RankedCandidate::score, (candidate, selected) -> 0.0))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new MaximalMarginalRelevanceRanker<>(0.5, 3, null, zeroSimilarity()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new MaximalMarginalRelevanceRanker<String, String>(
                    0.5, 3, RankedCandidate::score, null))
        .isInstanceOf(NullPointerException.class);
    var ranker =
        new MaximalMarginalRelevanceRanker<String, String>(
            0.5,
            2,
            candidate -> {
              throw new AssertionError("invalid inputs must not invoke callbacks");
            },
            zeroSimilarity());
    assertThatThrownBy(() -> ranker.rank(null, null, RankingContext.of(1)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ranker.rank(null, List.of(), null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                ranker.rank(
                    null, Arrays.asList((RankedCandidate<String>) null), RankingContext.of(1)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                ranker.rank(
                    null, List.of(ranked("a", 0.1), ranked("a", 0.2)), RankingContext.of(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique IDs");
    assertThatThrownBy(
            () ->
                ranker.rank(
                    null,
                    List.of(ranked("a", 0.1), ranked("b", 0.2), ranked("c", 0.3)),
                    RankingContext.of(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxCandidates");
    assertThat(ranker.rank(null, List.of(), RankingContext.of(1))).isEmpty();
  }

  @Test
  void shouldRejectNonNormalizedCallbackValuesAndSanitizeCallbackFailures() {
    var input = List.of(ranked("private-a", 0.9), ranked("private-b", 0.8));
    for (double value :
        List.of(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
      var invalidRelevance =
          new MaximalMarginalRelevanceRanker<String, String>(
              0.0, 2, candidate -> value, zeroSimilarity());
      assertThatThrownBy(() -> invalidRelevance.rank(null, input, RankingContext.of(1)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("MMR relevance failed or returned an invalid value")
          .hasNoCause();
      var invalidSimilarity =
          new MaximalMarginalRelevanceRanker<String, String>(
              0.5, 2, RankedCandidate::score, (candidate, selected) -> value);
      assertThatThrownBy(() -> invalidSimilarity.rank(null, input, RankingContext.of(2)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("MMR similarity failed or returned an invalid value")
          .hasNoCause();
    }
    var failingRelevance =
        new MaximalMarginalRelevanceRanker<String, String>(
            0.5,
            2,
            candidate -> {
              throw new IllegalArgumentException("private details");
            },
            zeroSimilarity());
    assertThatThrownBy(() -> failingRelevance.rank(null, input, RankingContext.of(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("private")
        .hasNoCause();
    var failingSimilarity =
        new MaximalMarginalRelevanceRanker<String, String>(
            0.5,
            2,
            RankedCandidate::score,
            (candidate, selected) -> {
              throw new IllegalArgumentException("private details");
            });
    assertThatThrownBy(() -> failingSimilarity.rank(null, input, RankingContext.of(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("private")
        .hasNoCause();
  }

  @Test
  void shouldStopBeforeCallbacksWhenBudgetExhaustedOrAlreadyInterrupted() {
    var ranker =
        new MaximalMarginalRelevanceRanker<String, String>(
            0.5,
            2,
            candidate -> {
              throw new AssertionError("cancelled ranking must not invoke callbacks");
            },
            zeroSimilarity());
    var input = List.of(ranked("a", 0.9));
    assertThatThrownBy(() -> ranker.rank(null, input, RankingContext.of(1, Duration.ZERO)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("budget exhausted");
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> ranker.rank(null, input, RankingContext.of(1)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void shouldStopImmediatelyAfterCallbackInterruptsWithoutReturningPartialSelection() {
    var calls = new AtomicInteger();
    var ranker =
        new MaximalMarginalRelevanceRanker<String, String>(
            0.5,
            3,
            RankedCandidate::score,
            (candidate, selected) -> {
              calls.incrementAndGet();
              Thread.currentThread().interrupt();
              return 0.0;
            });
    try {
      assertThatThrownBy(
              () ->
                  ranker.rank(
                      null,
                      List.of(ranked("a", 0.9), ranked("b", 0.8), ranked("c", 0.7)),
                      RankingContext.of(3)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("interrupted");
      assertThat(calls).hasValue(1);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void shouldComposeWithPinsAndQuotaInTheirExplicitPolicyOrder() {
    var ranker =
        new MaximalMarginalRelevanceRanker<String, String>(
            0.5,
            3,
            RankedCandidate::score,
            (candidate, selected) -> candidate.id().equals("b") ? 0.95 : 0.1);
    var ranked =
        ranker.rank(
            null,
            List.of(ranked("a", 1.0), ranked("b", 0.95), ranked("c", 0.8)),
            RankingContext.of(3));
    var pins = new PinnedRankingPolicy<String, String>(List.of("b"));
    var quota =
        new GroupQuotaPolicy<String, String, Boolean>(candidate -> candidate.id().equals("c"), 1);
    var pinsThenQuota = new CompositeRankingPolicy<>(List.of(pins, quota));
    var quotaThenPins = new CompositeRankingPolicy<>(List.of(quota, pins));

    assertThat(ranked).extracting(RankedCandidate::id).containsExactly("a", "c", "b");
    assertThat(pinsThenQuota.apply(null, ranked, RankingContext.of(2)))
        .extracting(RankedCandidate::id)
        .containsExactly("b", "c");
    assertThat(quotaThenPins.apply(null, ranked, RankingContext.of(2)))
        .extracting(RankedCandidate::id)
        .containsExactly("a", "c");
  }

  private static <ID>
      ToDoubleBiFunction<RankedCandidate<ID>, RankedCandidate<ID>> zeroSimilarity() {
    return (candidate, selected) -> 0.0;
  }

  private static <ID> RankedCandidate<ID> ranked(ID id, double score) {
    return new RankedCandidate<>(
        id, score, List.of(new SourceContribution("search", 1, 8.0)), Map.of("title", "product"));
  }

  private record OpaqueId(int value) {
    @Override
    public String toString() {
      throw new AssertionError("IDs must not be stringified");
    }
  }
}
