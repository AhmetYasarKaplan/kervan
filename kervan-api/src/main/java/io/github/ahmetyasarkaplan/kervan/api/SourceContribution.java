package io.github.ahmetyasarkaplan.kervan.api;

import java.util.Objects;

/**
 * The original rank and score contributed by one provider to a ranked candidate.
 *
 * @param source stable provider name
 * @param rank positive one-based rank in that provider's result
 * @param score finite score returned by that provider
 */
public record SourceContribution(String source, int rank, double score) {

  /** Validates the source name, rank, and score. */
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
  }
}
