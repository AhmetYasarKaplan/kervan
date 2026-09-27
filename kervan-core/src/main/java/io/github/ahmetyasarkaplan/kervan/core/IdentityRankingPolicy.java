package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import io.github.ahmetyasarkaplan.kervan.api.RankingPolicy;
import java.util.List;

/**
 * Leaves ranked candidates in their existing order for the pipeline's final result limit.
 *
 * @param <Q> application query type
 * @param <ID> canonical candidate identifier type
 */
public final class IdentityRankingPolicy<Q, ID> implements RankingPolicy<Q, ID> {

  /** Creates the default final policy. */
  public IdentityRankingPolicy() {}

  @Override
  public List<RankedCandidate<ID>> apply(
      Q query, List<RankedCandidate<ID>> candidates, RankingContext context) {
    return candidates;
  }
}
