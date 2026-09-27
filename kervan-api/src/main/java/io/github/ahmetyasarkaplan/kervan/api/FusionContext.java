package io.github.ahmetyasarkaplan.kervan.api;

import java.util.Map;
import java.util.Objects;

/**
 * Execution parameters provided to a fusion strategy.
 *
 * @param candidateLimit the maximum number of candidates to produce after fusion
 * @param sourceWeights positive finite weights keyed by stable retriever name; an absent weight is
 *     interpreted as 1.0 by score-based fusion
 */
public record FusionContext(int candidateLimit, Map<String, Double> sourceWeights) {

  /** Validates the output limit and takes an immutable snapshot of source weights. */
  public FusionContext {
    if (candidateLimit <= 0) {
      throw new IllegalArgumentException("candidateLimit must be positive: " + candidateLimit);
    }
    sourceWeights =
        Map.copyOf(Objects.requireNonNull(sourceWeights, "sourceWeights must not be null"));
    sourceWeights.forEach(
        (name, weight) -> {
          if (name.isBlank()) {
            throw new IllegalArgumentException("source weight name must not be blank");
          }
          if (!Double.isFinite(weight) || weight <= 0) {
            throw new IllegalArgumentException("source weight must be positive and finite");
          }
        });
  }

  /**
   * Creates a context without explicit source weights.
   *
   * @param candidateLimit positive maximum number of fused candidates
   */
  public FusionContext(int candidateLimit) {
    this(candidateLimit, Map.of());
  }

  /**
   * Creates a context without explicit source weights.
   *
   * @param candidateLimit positive maximum number of fused candidates
   * @return the fusion context
   */
  public static FusionContext of(int candidateLimit) {
    return new FusionContext(candidateLimit);
  }

  /**
   * Creates a context with positive finite weights keyed by provider name.
   *
   * @param candidateLimit positive maximum number of fused candidates
   * @param sourceWeights provider weights, copied into an immutable map
   * @return the fusion context
   */
  public static FusionContext of(int candidateLimit, Map<String, Double> sourceWeights) {
    return new FusionContext(candidateLimit, sourceWeights);
  }
}
