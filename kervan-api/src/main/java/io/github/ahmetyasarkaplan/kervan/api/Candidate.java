package io.github.ahmetyasarkaplan.kervan.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    sources = Set.copyOf(sources);
    sourceScores = Map.copyOf(sourceScores);
    attributes = Map.copyOf(attributes);
  }

  public static <ID> Candidate<ID> of(ID id, double score, String source) {
    Objects.requireNonNull(source, "source must not be null");
    return new Candidate<>(
        id,
        score,
        Set.of(source),
        Map.of(source, score),
        Map.of());
  }
}
