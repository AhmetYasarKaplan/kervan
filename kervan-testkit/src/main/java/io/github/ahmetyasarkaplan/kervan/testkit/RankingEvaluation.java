package io.github.ahmetyasarkaplan.kervan.testkit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Computes deterministic offline relevance metrics from explicitly judged canonical IDs. */
public final class RankingEvaluation {

  private RankingEvaluation() {}

  /**
   * Evaluates one ordered ranking against a query's relevance judgments.
   *
   * <p>Grades must be integers from 0 through 30; positive grades are relevant. Unjudged IDs
   * contribute zero relevance. Precision always divides by {@code k}, including short rankings.
   * Recall divides by all positively judged IDs. Reciprocal rank is zero if no relevant ID occurs
   * within the cutoff. DCG uses {@code (2^grade - 1) / log2(rank + 1)}, with one-based ranks. Its
   * ideal ranking includes all judgments, sorted by descending grade and truncated at {@code k}.
   * Recall and nDCG are zero when no positive judgments exist. Judged coverage divides by the
   * actual number of returned IDs within the cutoff; empty rankings have zero coverage.
   *
   * <p>The full ranking is validated, including IDs beyond the cutoff. IDs must have stable {@code
   * equals} and {@code hashCode} implementations. The supplied collections must not be mutated
   * during evaluation. Scores and ties are not inputs: the supplied ID order determines rank.
   *
   * @param <ID> canonical candidate identifier type
   * @param rankedIds unique IDs in best-first order
   * @param judgments canonical IDs mapped to relevance grades, including non-retrieved IDs
   * @param k positive evaluation cutoff
   * @return measurements for this query and cutoff
   * @throws NullPointerException if a collection, ID, or judgment grade is null
   * @throws IllegalArgumentException if {@code k} is not positive, any grade is outside 0..30, or
   *     the full ranking contains duplicate IDs
   */
  public static <ID> RankingMetrics evaluate(
      List<ID> rankedIds, Map<ID, Integer> judgments, int k) {
    Objects.requireNonNull(rankedIds, "rankedIds must not be null");
    Objects.requireNonNull(judgments, "judgments must not be null");
    if (k <= 0) {
      throw new IllegalArgumentException("k must be positive");
    }

    List<Integer> positiveGrades = new ArrayList<>();
    for (Map.Entry<ID, Integer> judgment : judgments.entrySet()) {
      Objects.requireNonNull(judgment.getKey(), "judgment ID must not be null");
      int grade = Objects.requireNonNull(judgment.getValue(), "judgment grade must not be null");
      if (grade < 0 || grade > 30) {
        throw new IllegalArgumentException("judgment grades must be between 0 and 30");
      }
      if (grade > 0) {
        positiveGrades.add(grade);
      }
    }

    Set<ID> seen = new HashSet<>();
    int rank = 0;
    int relevantCount = 0;
    int judgedCount = 0;
    double reciprocalRank = 0;
    double dcg = 0;
    for (ID id : rankedIds) {
      Objects.requireNonNull(id, "ranked ID must not be null");
      if (!seen.add(id)) {
        throw new IllegalArgumentException("rankedIds must not contain duplicate IDs");
      }
      rank++;
      if (rank <= k) {
        Integer grade = judgments.get(id);
        if (grade != null) {
          judgedCount++;
          if (grade > 0) {
            relevantCount++;
            if (reciprocalRank == 0) {
              reciprocalRank = 1.0 / rank;
            }
            dcg += discountedGain(grade, rank);
          }
        }
      }
    }

    positiveGrades.sort(Comparator.reverseOrder());
    double idealDcg = 0;
    for (int index = 0; index < Math.min(k, positiveGrades.size()); index++) {
      idealDcg += discountedGain(positiveGrades.get(index), index + 1);
    }
    int returnedCount = Math.min(k, rankedIds.size());
    return new RankingMetrics(
        (double) relevantCount / k,
        positiveGrades.isEmpty() ? 0 : (double) relevantCount / positiveGrades.size(),
        reciprocalRank,
        idealDcg == 0 ? 0 : Math.min(1, dcg / idealDcg),
        returnedCount == 0 ? 0 : (double) judgedCount / returnedCount);
  }

  private static double discountedGain(int grade, int rank) {
    return (Math.scalb(1.0, grade) - 1) / (Math.log(rank + 1.0) / Math.log(2));
  }
}
