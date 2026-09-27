package io.github.ahmetyasarkaplan.kervan.api;

import java.util.Map;
import java.util.Objects;

/**
 * An individual provider's candidate, identified by the application's canonical ID.
 *
 * @param <ID> canonical candidate identifier type
 * @param id canonical candidate identifier
 * @param score finite, higher-is-better score for score-based fusion; convert distance values with
 *     {@link ScoreDirection#orient(double)} before constructing a candidate
 * @param attributes immutable map of application-supplied hints; values are not deep copied
 */
public record SourceCandidate<ID>(ID id, double score, Map<String, Object> attributes) {

  /** Validates the candidate and copies its attribute map. */
  public SourceCandidate {
    Objects.requireNonNull(id, "id must not be null");
    if (!Double.isFinite(score)) {
      throw new IllegalArgumentException("score must be finite");
    }
    attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
  }

  /**
   * Creates a candidate without attributes.
   *
   * @param <ID> canonical candidate identifier type
   * @param id canonical candidate identifier
   * @param score finite provider score
   * @return the candidate
   */
  public static <ID> SourceCandidate<ID> of(ID id, double score) {
    return new SourceCandidate<>(id, score, Map.of());
  }
}
