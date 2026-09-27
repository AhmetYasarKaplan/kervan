package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.FusionContext;
import io.github.ahmetyasarkaplan.kervan.api.FusionStrategy;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalResult;
import java.util.List;
import java.util.Objects;

/**
 * Combines provider lists in their configured order, retaining the first score and position for
 * each canonical ID. It is a deterministic baseline strategy, not a relevance fusion algorithm.
 *
 * @param <ID> canonical candidate identifier type
 */
public final class FirstSeenFusion<ID> implements FusionStrategy<ID> {

  /** Creates a fusion strategy that follows configured provider order. */
  public FirstSeenFusion() {}

  @Override
  public List<RankedCandidate<ID>> fuse(List<RetrievalResult<ID>> results, FusionContext context) {
    Objects.requireNonNull(context, "context must not be null");
    List<RankedCandidate<ID>> merged = CandidateMerger.merge(results);
    return List.copyOf(merged.subList(0, Math.min(merged.size(), context.candidateLimit())));
  }
}
