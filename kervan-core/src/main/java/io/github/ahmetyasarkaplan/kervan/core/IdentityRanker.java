package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.Ranker;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import java.util.List;
import java.util.Objects;

/**
 * A pass-through ranker that preserves candidate ordering, truncating to the candidate limit.
 *
 * <p>The output is an unmodifiable snapshot. Selected candidates must be non-null. The query is
 * unused and may be null.
 *
 * @param <Q> the query input type.
 * @param <ID> the candidate identifier type.
 */
public final class IdentityRanker<Q, ID> implements Ranker<Q, ID> {

  /** Creates a ranker that preserves fusion order. */
  public IdentityRanker() {}

  @Override
  public List<RankedCandidate<ID>> rank(
      Q query, List<RankedCandidate<ID>> candidates, RankingContext context) {
    Objects.requireNonNull(candidates, "candidates must not be null");
    Objects.requireNonNull(context, "context must not be null");
    return List.copyOf(
        candidates.subList(0, Math.min(candidates.size(), context.candidateLimit())));
  }
}
