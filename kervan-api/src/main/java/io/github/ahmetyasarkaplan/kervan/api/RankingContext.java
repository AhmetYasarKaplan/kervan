package io.github.ahmetyasarkaplan.kervan.api;

/**
 * Execution parameters provided to a ranker stage.
 *
 * @param candidateLimit the maximum number of candidates to produce after ranking.
 */
public record RankingContext(int candidateLimit) {

  public RankingContext {
    if (candidateLimit <= 0) {
      throw new IllegalArgumentException("candidateLimit must be positive: " + candidateLimit);
    }
  }

  /**
   * Convenience factory method.
   */
  public static RankingContext of(int candidateLimit) {
    return new RankingContext(candidateLimit);
  }
}
