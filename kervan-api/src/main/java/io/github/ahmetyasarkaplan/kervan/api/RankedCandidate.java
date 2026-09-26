package io.github.ahmetyasarkaplan.kervan.api;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A post-fusion candidate with an ordered contribution from each source that found it. */
public record RankedCandidate<ID>(
    ID id, double score, List<SourceContribution> contributions, Map<String, Object> attributes) {

  public RankedCandidate {
    Objects.requireNonNull(id, "id must not be null");
    if (!Double.isFinite(score)) {
      throw new IllegalArgumentException("score must be finite");
    }
    contributions =
        List.copyOf(Objects.requireNonNull(contributions, "contributions must not be null"));
    if (contributions.isEmpty()) {
      throw new IllegalArgumentException("contributions must not be empty");
    }
    var sources = new HashSet<String>();
    for (SourceContribution contribution : contributions) {
      if (!sources.add(contribution.source())) {
        throw new IllegalArgumentException("contributions must have unique sources");
      }
    }
    attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
  }
}
