package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.FusionContext;
import io.github.ahmetyasarkaplan.kervan.api.FusionStrategy;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalResult;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Cross-provider reciprocal rank fusion using one-based source ranks. */
public final class ReciprocalRankFusion<ID> implements FusionStrategy<ID> {

  private final int rankConstant;

  public ReciprocalRankFusion(int rankConstant) {
    if (rankConstant <= 0) {
      throw new IllegalArgumentException("rankConstant must be positive");
    }
    this.rankConstant = rankConstant;
  }

  public int rankConstant() {
    return rankConstant;
  }

  @Override
  public List<RankedCandidate<ID>> fuse(List<RetrievalResult<ID>> results, FusionContext context) {
    Objects.requireNonNull(context, "context must not be null");
    List<RankedCandidate<ID>> merged = CandidateMerger.merge(results);
    List<RankedCandidate<ID>> scored = new ArrayList<>(merged.size());
    for (RankedCandidate<ID> candidate : merged) {
      double score = 0;
      for (SourceContribution contribution : candidate.contributions()) {
        score += 1.0 / ((double) rankConstant + contribution.rank());
      }
      scored.add(
          new RankedCandidate<>(
              candidate.id(), score, candidate.contributions(), candidate.attributes()));
    }
    return FusionOrdering.sortAndLimit(scored, results, context.candidateLimit());
  }
}
