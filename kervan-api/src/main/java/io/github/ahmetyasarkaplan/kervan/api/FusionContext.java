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

  public FusionContext(int candidateLimit) {
    this(candidateLimit, Map.of());
  }

  public static FusionContext of(int candidateLimit) {
    return new FusionContext(candidateLimit);
  }

  public static FusionContext of(int candidateLimit, Map<String, Double> sourceWeights) {
    return new FusionContext(candidateLimit, sourceWeights);
  }
}
