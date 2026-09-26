package io.github.ahmetyasarkaplan.kervan.api;

import java.util.Objects;

/** A source candidate with its original one-based position assigned by the core. */
public record SourceHit<ID>(SourceCandidate<ID> candidate, int rank) {

  public SourceHit {
    Objects.requireNonNull(candidate, "candidate must not be null");
    if (rank <= 0) {
      throw new IllegalArgumentException("rank must be positive");
    }
  }
}
