package io.github.ahmetyasarkaplan.kervan.api;

import java.util.Map;
import java.util.Objects;

/** An individual provider's candidate, identified by the application's canonical ID. */
public record SourceCandidate<ID>(ID id, double score, Map<String, Object> attributes) {

  public SourceCandidate {
    Objects.requireNonNull(id, "id must not be null");
    if (!Double.isFinite(score)) {
      throw new IllegalArgumentException("score must be finite");
    }
    attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
  }

  public static <ID> SourceCandidate<ID> of(ID id, double score) {
    return new SourceCandidate<>(id, score, Map.of());
  }
}
