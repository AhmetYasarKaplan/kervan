package io.github.ahmetyasarkaplan.kervan.api;

import java.util.List;

/**
 * Functional contract for candidate rerankers or downstream scoring policies.
 *
 * @param <Q> the query input type.
 * @param <ID> the candidate identifier type.
 */
@FunctionalInterface
public interface Ranker<Q, ID> {

  /**
   * Reranks the candidates matching the given query within the provided context.
   *
   * @param query the search or recommendation query input.
   * @param candidates ordered candidate inputs to rerank.
   * @param context ranking execution parameters including candidate budget limits.
   * @return the reranked candidate list.
   */
  List<RankedCandidate<ID>> rank(
      Q query, List<RankedCandidate<ID>> candidates, RankingContext context);
}
