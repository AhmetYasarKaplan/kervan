package io.github.ahmetyasarkaplan.kervan.api;

/** The direction in which a provider's numeric score indicates a better match. */
public enum ScoreDirection {
  /** Larger values are better, as with cosine similarity. */
  HIGHER_IS_BETTER,
  /** Smaller values are better, as with Euclidean distance. */
  LOWER_IS_BETTER;

  /**
   * Converts a finite provider value to a higher-is-better signal for score-based fusion.
   *
   * @param value finite provider score or distance
   * @return an oriented finite score
   */
  public double orient(double value) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("provider score must be finite");
    }
    return this == LOWER_IS_BETTER ? -value : value;
  }
}
