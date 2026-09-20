package io.github.ahmetyasarkaplan.kervan.api;

/**
 * Execution parameters provided to a fusion strategy.
 *
 * @param candidateLimit the maximum number of candidates to produce after fusion.
 */
public record FusionContext(int candidateLimit) {

  public FusionContext {
    if (candidateLimit <= 0) {
      throw new IllegalArgumentException("candidateLimit must be positive: " + candidateLimit);
    }
  }

  /**
   * Convenience factory method.
   */
  public static FusionContext of(int candidateLimit) {
    return new FusionContext(candidateLimit);
  }
}
