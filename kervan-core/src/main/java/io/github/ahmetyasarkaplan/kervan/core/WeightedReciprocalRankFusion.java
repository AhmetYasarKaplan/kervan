package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.FusionContext;
import io.github.ahmetyasarkaplan.kervan.api.FusionStrategy;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalResult;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Cross-provider reciprocal rank fusion that multiplies each source's contribution by its positive
 * weight. Sources without an explicit weight use 1.0.
 *
 * @param <ID> canonical candidate identifier type
 */
public final class WeightedReciprocalRankFusion<ID> implements FusionStrategy<ID> {

  private final int rankConstant;

  /**
   * Creates a weighted RRF strategy with a positive rank constant.
   *
   * @param rankConstant positive denominator offset for each source rank
   */
  public WeightedReciprocalRankFusion(int rankConstant) {
    if (rankConstant <= 0) {
      throw new IllegalArgumentException("rankConstant must be positive");
    }
    this.rankConstant = rankConstant;
  }

  /**
   * Returns the configured denominator offset.
   *
   * @return positive rank constant
   */
  public int rankConstant() {
    return rankConstant;
  }

  @Override
  public List<RankedCandidate<ID>> fuse(List<RetrievalResult<ID>> results, FusionContext context) {
    Objects.requireNonNull(context, "context must not be null");
    List<RankedCandidate<ID>> merged = CandidateMerger.merge(results);
    Set<String> knownSources = new HashSet<>();
    for (RetrievalResult<ID> result : results) {
      knownSources.add(result.retrieverName());
    }
    if (!knownSources.containsAll(context.sourceWeights().keySet())) {
      throw new IllegalArgumentException("sourceWeights contains an unknown source");
    }

    List<RankedCandidate<ID>> scored = new ArrayList<>(merged.size());
    for (RankedCandidate<ID> candidate : merged) {
      double score = 0;
      for (SourceContribution contribution : candidate.contributions()) {
        double weight = context.sourceWeights().getOrDefault(contribution.source(), 1.0);
        score += weight / ((double) rankConstant + contribution.rank());
        if (!Double.isFinite(score)) {
          throw new IllegalArgumentException("weighted fused score must remain finite");
        }
      }
      scored.add(
          new RankedCandidate<>(
              candidate.id(), score, candidate.contributions(), candidate.attributes()));
    }
    return FusionOrdering.sortAndLimit(scored, results, context.candidateLimit());
  }
}
