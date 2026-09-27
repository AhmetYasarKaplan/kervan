package io.github.ahmetyasarkaplan.kervan.api;

import java.util.Objects;

/**
 * A source candidate with its original one-based position assigned by the core.
 *
 * @param <ID> canonical candidate identifier type
 * @param candidate candidate returned by the provider
 * @param rank positive one-based position in the provider result
 */
public record SourceHit<ID>(SourceCandidate<ID> candidate, int rank) {

  /** Validates the candidate and source rank. */
  public SourceHit {
    Objects.requireNonNull(candidate, "candidate must not be null");
    if (rank <= 0) {
      throw new IllegalArgumentException("rank must be positive");
    }
  }
}
