package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.BatchScorer;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import io.github.ahmetyasarkaplan.kervan.api.RerankingScore;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BatchScoringRankerTest {

  @Test
  void shouldScoreFullWindowOnceBeforeLimitAndPreserveBothScoreKindsAndSourceHints() {
    var contributions =
        List.of(
            new SourceContribution("search", 1, 8.0, Map.of("title", "search title")),
            new SourceContribution("vector", 2, 0.3, Map.of("title", "vector title")));
    var first = new RankedCandidate<>("first", 0.8, contributions, Map.of("title", "search title"));
    var last = ranked("last", 0.1);
    var input = new ArrayList<>(List.of(first, last));
    var calls = new AtomicInteger();
    var context = RankingContext.of(1);
    BatchScorer<String, String> scorer =
        (query, candidates, executionContext) -> {
          calls.incrementAndGet();
          assertThat(query).isEqualTo("query");
          assertThat(candidates).containsExactly(first, last);
          assertThat(executionContext).isSameAs(context);
          assertThatThrownBy(candidates::clear).isInstanceOf(UnsupportedOperationException.class);
          assertThat(candidates.getFirst().contributions()).isEqualTo(contributions);
          return Map.of("last", 42.0, "first", -2.0);
        };

    var output = new BatchScoringRanker<>(scorer).rank("query", input, context);

    assertThat(calls).hasValue(1);
    assertThat(output).extracting(RankedCandidate::id).containsExactly("last");
    assertThat(output.getFirst().score()).isEqualTo(42.0);
    assertThat(output.getFirst().rerankingScore()).contains(new RerankingScore(0.1, 42.0));
    assertThat(output.getFirst().contributions()).isEqualTo(last.contributions());
    assertThat(output.getFirst().attributes()).isEqualTo(last.attributes());
    assertThat(input).containsExactly(first, last);
    assertThat(first.score()).isEqualTo(0.8);
    assertThat(first.rerankingScore()).isEmpty();
    input.clear();
    assertThat(output).hasSize(1);
    assertThatThrownBy(output::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldKeepFusedOrderForTiesWithoutComparingOpaqueIds() {
    var first = new OpaqueId(1);
    var second = new OpaqueId(2);
    var third = new OpaqueId(3);
    var ranker =
        new BatchScoringRanker<String, OpaqueId>(
            (query, candidates, context) -> Map.of(third, 2.0, second, -0.0, first, 0.0));

    var output =
        ranker.rank(
            null,
            List.of(ranked(first, 0.9), ranked(second, 0.8), ranked(third, 0.7)),
            RankingContext.of(10));

    assertThat(output).extracting(RankedCandidate::id).containsExactly(third, first, second);
  }

  @Test
  void shouldPreserveOriginalFusionScoreWhenRerankedAgain() {
    var ranker =
        new BatchScoringRanker<String, String>((query, candidates, context) -> Map.of("id", 17.0));
    var first = ranker.rank(null, List.of(ranked("id", 0.4)), RankingContext.of(1));
    var output =
        new BatchScoringRanker<String, String>((query, candidates, context) -> Map.of("id", -5.0))
            .rank(null, first, RankingContext.of(1));

    assertThat(output.getFirst().rerankingScore()).contains(new RerankingScore(0.4, -5.0));
  }

  @Test
  void shouldSkipScorerForEmptyInput() {
    var ranker =
        new BatchScoringRanker<String, String>(
            (query, candidates, context) -> {
              throw new AssertionError("empty input must not invoke the scorer");
            });
    assertThat(ranker.rank(null, List.of(), RankingContext.of(1))).isEmpty();
  }

  @Test
  void shouldRejectMissingUnknownNullAndNonFiniteScoresWithoutLeakingIds() {
    var nullScore = new HashMap<String, Double>();
    nullScore.put("id", null);
    var nullId = new HashMap<String, Double>();
    nullId.put(null, 1.0);
    List<Map<String, Double>> invalid =
        Arrays.asList(
            null,
            Map.of(),
            Map.of("private-unknown-id", 1.0),
            Map.of("id", 1.0, "private-unknown-id", 2.0),
            nullScore,
            nullId,
            Map.of("id", Double.NaN),
            Map.of("id", Double.POSITIVE_INFINITY),
            Map.of("id", Double.NEGATIVE_INFINITY));
    for (Map<String, Double> scores : invalid) {
      var ranker = new BatchScoringRanker<String, String>((query, candidates, context) -> scores);
      assertThatThrownBy(() -> ranker.rank(null, List.of(ranked("id", 0.1)), RankingContext.of(1)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("batch scoring failed or returned invalid scores")
          .hasNoCause();
    }
  }

  @Test
  void shouldSanitizeCheckedAndUncheckedClientFailures() {
    for (Exception failure :
        List.of(new IOException("private query and endpoint"), new RuntimeException("secret"))) {
      var ranker =
          new BatchScoringRanker<String, String>(
              (query, candidates, context) -> {
                throw failure;
              });
      assertThatThrownBy(() -> ranker.rank(null, List.of(ranked("id", 0.1)), RankingContext.of(1)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("batch scoring failed or returned invalid scores")
          .hasNoCause();
    }
  }

  @Test
  void shouldRestoreInterruptStatusWhenClientThrowsInterruptedException() {
    var ranker =
        new BatchScoringRanker<String, String>(
            (query, candidates, context) -> {
              throw new InterruptedException("private client details");
            });
    try {
      assertThatThrownBy(() -> ranker.rank(null, List.of(ranked("id", 0.1)), RankingContext.of(1)))
          .isInstanceOf(IllegalStateException.class)
          .hasNoCause();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void shouldRejectDuplicateIdsBeforeCallingScorer() {
    var ranker =
        new BatchScoringRanker<String, String>(
            (query, candidates, context) -> {
              throw new AssertionError("duplicate input must not invoke the scorer");
            });
    assertThatThrownBy(
            () ->
                ranker.rank(
                    null, List.of(ranked("id", 0.1), ranked("id", 0.2)), RankingContext.of(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique IDs");
  }

  @Test
  void shouldValidateRequiredArgumentsBeforeCallingScorer() {
    assertThatThrownBy(() -> new BatchScoringRanker<String, String>(null))
        .isInstanceOf(NullPointerException.class);
    var ranker = new BatchScoringRanker<String, String>((query, candidates, context) -> Map.of());
    assertThatThrownBy(() -> ranker.rank(null, null, RankingContext.of(1)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ranker.rank(null, List.of(), null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                ranker.rank(
                    null, Arrays.asList((RankedCandidate<String>) null), RankingContext.of(1)))
        .isInstanceOf(NullPointerException.class);
  }

  private record OpaqueId(int value) {
    @Override
    public String toString() {
      throw new AssertionError("candidate IDs must not be stringified");
    }
  }

  private static <ID> RankedCandidate<ID> ranked(ID id, double score) {
    return new RankedCandidate<>(
        id, score, List.of(new SourceContribution("search", 1, 8.0)), Map.of("title", "product"));
  }
}
