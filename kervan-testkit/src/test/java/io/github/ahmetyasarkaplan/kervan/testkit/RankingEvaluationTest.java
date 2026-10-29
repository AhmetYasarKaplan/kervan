package io.github.ahmetyasarkaplan.kervan.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RankingEvaluationTest {

  private static final Map<String, Integer> JUDGMENTS =
      Map.of("a", 3, "b", 2, "c", 1, "irrelevant", 0);

  @Test
  void comparesBaselineAndRerankedResultsAgainstHandCalculatedJudgments() {
    RankingMetrics baseline =
        RankingEvaluation.evaluate(List.of("irrelevant", "unjudged", "b", "a"), JUDGMENTS, 3);
    RankingMetrics reranked = RankingEvaluation.evaluate(List.of("a", "b", "c"), JUDGMENTS, 3);

    // Baseline: only b is relevant, at rank 3. DCG = 3 / log2(4) = 1.5.
    // Ideal DCG = 7 + 3 / log2(3) + 1 / log2(4) = 9.392789260714373.
    assertThat(baseline.precisionAtK()).isCloseTo(1.0 / 3, within(1e-12));
    assertThat(baseline.recallAtK()).isCloseTo(1.0 / 3, within(1e-12));
    assertThat(baseline.reciprocalRankAtK()).isCloseTo(1.0 / 3, within(1e-12));
    assertThat(baseline.ndcgAtK()).isCloseTo(0.159696971619899, within(1e-12));
    assertThat(baseline.judgedCoverageAtK()).isCloseTo(2.0 / 3, within(1e-12));
    assertThat(reranked).isEqualTo(new RankingMetrics(1, 1, 1, 1, 1));
    assertThat(reranked.ndcgAtK()).isGreaterThan(baseline.ndcgAtK());
  }

  @Test
  void distinguishesGradedOrderingWhenBinaryMetricsAreIdentical() {
    RankingMetrics reversed = RankingEvaluation.evaluate(List.of("c", "b", "a"), JUDGMENTS, 3);

    // DCG = 1 + 3 / log2(3) + 7 / log2(4) = 6.392789260714373.
    assertThat(reversed.precisionAtK()).isEqualTo(1);
    assertThat(reversed.recallAtK()).isEqualTo(1);
    assertThat(reversed.reciprocalRankAtK()).isEqualTo(1);
    assertThat(reversed.ndcgAtK()).isCloseTo(0.680606056760201, within(1e-12));
  }

  @Test
  void shortRankingsUseRequestedPrecisionCutoffAndFullJudgmentRecallDenominator() {
    RankingMetrics metrics = RankingEvaluation.evaluate(List.of("a"), JUDGMENTS, 5);

    assertThat(metrics.precisionAtK()).isEqualTo(0.2);
    assertThat(metrics.recallAtK()).isCloseTo(1.0 / 3, within(1e-12));
    assertThat(metrics.reciprocalRankAtK()).isEqualTo(1);
    assertThat(metrics.ndcgAtK()).isCloseTo(7.0 / 9.392789260714373, within(1e-12));
    assertThat(metrics.judgedCoverageAtK()).isEqualTo(1);
  }

  @Test
  void absentRelevantJudgmentsAndEmptyRankingsProduceFiniteZeros() {
    assertThat(RankingEvaluation.evaluate(List.of(), JUDGMENTS, 3))
        .isEqualTo(new RankingMetrics(0, 0, 0, 0, 0));
    assertThat(RankingEvaluation.evaluate(List.of("unknown"), Map.of(), 3))
        .isEqualTo(new RankingMetrics(0, 0, 0, 0, 0));
    assertThat(RankingEvaluation.evaluate(List.of("irrelevant"), Map.of("irrelevant", 0), 3))
        .isEqualTo(new RankingMetrics(0, 0, 0, 0, 1));
  }

  @Test
  void relevantResultsBeyondCutoffDoNotContribute() {
    assertThat(RankingEvaluation.evaluate(List.of("irrelevant", "a"), JUDGMENTS, 1))
        .isEqualTo(new RankingMetrics(0, 0, 0, 0, 1));
  }

  @Test
  void acceptsMaximumGradeWithoutOverflow() {
    assertThat(RankingEvaluation.evaluate(List.of("a"), Map.of("a", 30), 1))
        .isEqualTo(new RankingMetrics(1, 1, 1, 1, 1));
  }

  @Test
  void rejectsInvalidCutoffsAndGradesIncludingNonRetrievedJudgments() {
    for (int k : List.of(0, -1)) {
      assertThatThrownBy(() -> RankingEvaluation.evaluate(List.of(), Map.of(), k))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("k must be positive");
    }
    for (int grade : List.of(-1, 31, Integer.MAX_VALUE)) {
      assertThatThrownBy(
              () -> RankingEvaluation.evaluate(List.of("a"), Map.of("not-retrieved", grade), 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("between 0 and 30");
    }
  }

  @Test
  void rejectsDuplicateIdsEvenWhenTheyOccurBeyondCutoff() {
    for (List<String> ranking : List.of(List.of("a", "a"), List.of("a", "b", "b"))) {
      assertThatThrownBy(() -> RankingEvaluation.evaluate(ranking, JUDGMENTS, 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("duplicate");
    }
  }

  @Test
  void rejectsNullCollectionsIdsAndGradesIncludingBeyondCutoff() {
    assertThatThrownBy(() -> RankingEvaluation.evaluate(null, JUDGMENTS, 1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RankingEvaluation.evaluate(List.of(), null, 1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                RankingEvaluation.evaluate(new ArrayList<>(Arrays.asList("a", null)), JUDGMENTS, 1))
        .isInstanceOf(NullPointerException.class);

    Map<String, Integer> nullId = new HashMap<>();
    nullId.put(null, 1);
    assertThatThrownBy(() -> RankingEvaluation.evaluate(List.of(), nullId, 1))
        .isInstanceOf(NullPointerException.class);
    Map<String, Integer> nullGrade = new HashMap<>();
    nullGrade.put("not-retrieved", null);
    assertThatThrownBy(() -> RankingEvaluation.evaluate(List.of(), nullGrade, 1))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejectsNonFiniteAndOutOfRangeMetricValues() {
    for (double invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
      assertThatThrownBy(() -> new RankingMetrics(invalid, 0, 0, 0, 0))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
