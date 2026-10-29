package io.github.ahmetyasarkaplan.kervan.api;

import java.util.List;
import java.util.Map;

/**
 * Application-owned scoring of a complete fused candidate window in one call.
 *
 * <p>The application can hydrate candidate IDs and call any local or remote model. Kervan imposes
 * no document schema or model client. Implementations should respect {@link
 * RankingContext#remainingBudget()} and respond to interruption. Higher scores indicate better
 * candidates.
 *
 * @param <Q> query input type
 * @param <ID> canonical candidate identifier type
 */
@FunctionalInterface
public interface BatchScorer<Q, ID> {

  /**
   * Scores every candidate in the immutable input window, preserving ID-based correspondence.
   *
   * @param query application query, which may be null if the application permits it
   * @param candidates immutable fused candidate window, before the final output limit is applied
   * @param context ranking limits and execution budget
   * @return exactly one finite non-null score for each input ID, with no additional IDs
   * @throws Exception if hydration or model scoring fails
   */
  Map<ID, Double> score(Q query, List<RankedCandidate<ID>> candidates, RankingContext context)
      throws Exception;
}
