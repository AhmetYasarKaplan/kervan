package io.github.ahmetyasarkaplan.kervan.api;

/**
 * Execution parameters provided to a ranker or final policy stage.
 *
 * @param candidateLimit the maximum number of candidates to produce after ranking.
 */
public record RankingContext(int candidateLimit) {

  /** Validates the candidate limit. */
  public RankingContext {
    if (candidateLimit <= 0) {
      throw new IllegalArgumentException("candidateLimit must be positive: " + candidateLimit);
    }
  }

  /**
   * Creates a ranking context with a positive output limit.
   *
   * @param candidateLimit maximum number of final candidates requested from the stage
   * @return the ranking context
   */
  public static RankingContext of(int candidateLimit) {
    return new RankingContext(candidateLimit);
  }
}
