package io.github.ahmetyasarkaplan.kervan.api;

import java.util.List;
import java.util.Objects;

/**
 * A pass-through ranker that preserves candidate ordering, truncating to the candidate limit.
 *
 * @param <Q> the query input type.
 * @param <ID> the candidate identifier type.
 */
public final class IdentityRanker<Q, ID> implements Ranker<Q, ID> {

  @Override
  public List<Candidate<ID>> rank(
      Q query, List<Candidate<ID>> candidates, RankingContext context) {
    Objects.requireNonNull(candidates, "candidates must not be null");
    Objects.requireNonNull(context, "context must not be null");
    return candidates.stream().limit(context.candidateLimit()).toList();
  }
}
