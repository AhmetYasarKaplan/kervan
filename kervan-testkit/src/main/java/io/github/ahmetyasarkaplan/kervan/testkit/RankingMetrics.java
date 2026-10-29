package io.github.ahmetyasarkaplan.kervan.testkit;

/**
 * Offline relevance measurements for one query at a caller-selected cutoff.
 *
 * @param precisionAtK relevant retrieved IDs divided by the requested cutoff
 * @param recallAtK relevant retrieved IDs divided by all positively judged IDs
 * @param reciprocalRankAtK reciprocal rank of the first relevant ID within the cutoff
 * @param ndcgAtK exponential-gain DCG divided by the ideal DCG within the cutoff
 * @param judgedCoverageAtK explicitly judged fraction of the returned IDs within the cutoff
 */
public record RankingMetrics(
    double precisionAtK,
    double recallAtK,
    double reciprocalRankAtK,
    double ndcgAtK,
    double judgedCoverageAtK) {

  /** Validates that all measurements are finite fractions between zero and one. */
  public RankingMetrics {
    requireFraction(precisionAtK);
    requireFraction(recallAtK);
    requireFraction(reciprocalRankAtK);
    requireFraction(ndcgAtK);
    requireFraction(judgedCoverageAtK);
  }

  private static void requireFraction(double value) {
    if (!Double.isFinite(value) || value < 0 || value > 1) {
      throw new IllegalArgumentException("metrics must be finite values between 0 and 1");
    }
  }
}
