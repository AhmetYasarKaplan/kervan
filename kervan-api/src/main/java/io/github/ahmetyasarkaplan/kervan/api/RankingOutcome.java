package io.github.ahmetyasarkaplan.kervan.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Content-free outcome of one ranking stage. Failed or skipped work has zero output candidates.
 *
 * @param status sanitized completion category
 * @param inputCandidates number of candidates offered to the ranker
 * @param outputCandidates number successfully produced by the ranker
 * @param duration elapsed ranking time
 */
public record RankingOutcome(
    RankingStatus status, int inputCandidates, int outputCandidates, Duration duration) {
  /** Validates bounded counts and elapsed time. */
  public RankingOutcome {
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(duration, "duration must not be null");
    if (inputCandidates < 0 || outputCandidates < 0 || duration.isNegative()) {
      throw new IllegalArgumentException("counts and duration must not be negative");
    }
    if (status != RankingStatus.SUCCESS && outputCandidates != 0) {
      throw new IllegalArgumentException("unsuccessful ranking must have zero output candidates");
    }
    if (status == RankingStatus.NOT_REPORTED && (inputCandidates != 0 || !duration.isZero())) {
      throw new IllegalArgumentException("unreported ranking must have zero counts and duration");
    }
  }

  /**
   * Returns an absent outcome for compatibility with externally constructed results.
   *
   * @return absent ranking outcome
   */
  public static RankingOutcome notReported() {
    return new RankingOutcome(RankingStatus.NOT_REPORTED, 0, 0, Duration.ZERO);
  }

  /**
   * Whether fused fallback replaced failed, timed out, or skipped ranking.
   *
   * @return whether ranking degraded
   */
  public boolean isDegraded() {
    return status == RankingStatus.FAILED
        || status == RankingStatus.TIMED_OUT
        || status == RankingStatus.SKIPPED_NO_BUDGET;
  }

  /**
   * Whether this outcome represents use of fused fallback in a successful pipeline result.
   *
   * @return whether fallback was used
   */
  public boolean fallbackUsed() {
    return isDegraded();
  }
}
