package io.github.ahmetyasarkaplan.kervan.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A candidate with a finite score and finite original source scores.
 *
 * <p>Collections are defensively copied and unmodifiable. IDs and attribute values are not deep
 * copied; callers should supply immutable values. Source collections do not define ranking order.
 *
 * @param <ID> the candidate identifier type
 * @param id non-null candidate identifier
 * @param score the current score; negative finite values are allowed
 * @param sources the contributing sources
 * @param sourceScores the original scores from contributing sources
 * @param attributes application-specific metadata
 */
public record Candidate<ID>(
    ID id,
    double score,
    Set<String> sources,
    Map<String, Double> sourceScores,
    Map<String, Object> attributes) {
  public Candidate {
    Objects.requireNonNull(id, "id must not be null");
    if (Double.isNaN(score) || Double.isInfinite(score)) {
      throw new IllegalArgumentException("score must be a finite number: " + score);
    }
    sources = Set.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
    sourceScores =
        Map.copyOf(Objects.requireNonNull(sourceScores, "sourceScores must not be null"));
    attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
    if (sourceScores.values().stream().anyMatch(value -> !Double.isFinite(value))) {
      throw new IllegalArgumentException("sourceScores must contain only finite numbers");
    }
  }

  public static <ID> Candidate<ID> of(ID id, double score, String source) {
    Objects.requireNonNull(source, "source must not be null");
    return new Candidate<>(id, score, Set.of(source), Map.of(source, score), Map.of());
  }
}
