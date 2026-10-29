package io.github.ahmetyasarkaplan.kervan.reranking;

import io.github.ahmetyasarkaplan.kervan.api.RankingContext;

/**
 * Application-owned conversion of a query to provider text.
 *
 * @param <Q> query input type
 */
@FunctionalInterface
public interface QueryMapper<Q> {
  /**
   * Maps a query within the remaining ranking budget.
   *
   * @param query application query
   * @param context decreasing execution budget; callbacks must cooperate with it and interruption
   * @return non-null query text
   * @throws Exception if conversion fails
   */
  String map(Q query, RankingContext context) throws Exception;
}
