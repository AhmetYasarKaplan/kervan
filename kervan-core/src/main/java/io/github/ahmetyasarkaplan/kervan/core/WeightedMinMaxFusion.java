package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.FusionContext;
import io.github.ahmetyasarkaplan.kervan.api.FusionStrategy;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalResult;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Weights scores normalized to [0, 1] within each provider's retrieved window. A source whose
 * scores are all equal gives every returned candidate a normalized score of 1.0.
 *
 * @param <ID> canonical candidate identifier type
 */
public final class WeightedMinMaxFusion<ID> implements FusionStrategy<ID> {

  /** Creates a strategy that normalizes each provider's retrieved score window. */
  public WeightedMinMaxFusion() {}

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

    Map<String, ScoreRange> ranges = new HashMap<>();
    for (RankedCandidate<ID> candidate : merged) {
      for (SourceContribution contribution : candidate.contributions()) {
        ranges
            .computeIfAbsent(contribution.source(), ignored -> new ScoreRange())
            .include(contribution.score());
      }
    }

    List<RankedCandidate<ID>> scored = new ArrayList<>(merged.size());
    for (RankedCandidate<ID> candidate : merged) {
      double score = 0;
      for (SourceContribution contribution : candidate.contributions()) {
        double weight = context.sourceWeights().getOrDefault(contribution.source(), 1.0);
        double term = weight * ranges.get(contribution.source()).normalize(contribution.score());
        score += term;
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

  private static final class ScoreRange {
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;

    private void include(double score) {
      min = Math.min(min, score);
      max = Math.max(max, score);
    }

    private double normalize(double score) {
      if (min == max) {
        return 1.0;
      }
      double span = max - min;
      double normalized;
      if (Double.isFinite(span)) {
        normalized = (score - min) / span;
      } else {
        // Dividing by the largest magnitude avoids infinity / infinity for finite extremes.
        double scale = Math.max(Math.abs(min), Math.abs(max));
        normalized = (score / scale - min / scale) / (max / scale - min / scale);
      }
      if (!Double.isFinite(normalized)) {
        throw new IllegalArgumentException("normalized score must remain finite");
      }
      return Math.max(0.0, Math.min(1.0, normalized));
    }
  }
}
