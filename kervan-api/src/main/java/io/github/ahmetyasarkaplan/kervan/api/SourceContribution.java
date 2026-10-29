package io.github.ahmetyasarkaplan.kervan.api;

import java.util.Map;
import java.util.Objects;

/**
 * The original rank and score contributed by one provider to a ranked candidate.
 *
 * @param source stable provider name
 * @param rank positive one-based rank in that provider's result
 * @param score finite score returned by that provider
 * @param attributes immutable snapshot of this provider's hints; values are not deep copied
 */
public record SourceContribution(
    String source, int rank, double score, Map<String, Object> attributes) {

  /**
   * Creates a contribution without provider attributes.
   *
   * @param source stable provider name
   * @param rank positive one-based provider rank
   * @param score finite provider score
   */
  public SourceContribution(String source, int rank, double score) {
    this(source, rank, score, Map.of());
  }

  /** Validates the source name, rank, and score, and snapshots provider attributes. */
  public SourceContribution {
    Objects.requireNonNull(source, "source must not be null");
    if (source.isBlank()) {
      throw new IllegalArgumentException("source must not be blank");
    }
    if (rank <= 0) {
      throw new IllegalArgumentException("rank must be positive");
    }
    if (!Double.isFinite(score)) {
      throw new IllegalArgumentException("score must be finite");
    }
    attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
  }
}
