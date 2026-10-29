package io.github.ahmetyasarkaplan.kervan.api;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A post-fusion candidate with an ordered contribution from each source that found it.
 *
 * @param <ID> canonical candidate identifier type
 * @param id canonical candidate identifier
 * @param score finite current score, either fused or supplied by a ranker
 * @param contributions nonempty source contributions in provider order
 * @param attributes immutable map of application-supplied ranking hints; values are not deep copied
 * @param rerankingScore optional original fusion and reranking scores
 */
public record RankedCandidate<ID>(
    ID id,
    double score,
    List<SourceContribution> contributions,
    Map<String, Object> attributes,
    Optional<RerankingScore> rerankingScore) {

  /**
   * Creates a candidate without separate reranking scores.
   *
   * @param id canonical candidate identifier
   * @param score finite current score
   * @param contributions nonempty source contributions
   * @param attributes application-supplied ranking hints
   */
  public RankedCandidate(
      ID id, double score, List<SourceContribution> contributions, Map<String, Object> attributes) {
    this(id, score, contributions, attributes, Optional.empty());
  }

  /** Validates the fused candidate and takes immutable snapshots of its collections. */
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
    Objects.requireNonNull(rerankingScore, "rerankingScore must not be null");
    if (rerankingScore.isPresent()
        && Double.compare(score, rerankingScore.orElseThrow().rerankScore()) != 0) {
      throw new IllegalArgumentException("score must match rerankingScore.rerankScore");
    }
  }
}
