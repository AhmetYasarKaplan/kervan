package io.github.ahmetyasarkaplan.kervan.reranking;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import java.util.List;
import java.util.Map;

/**
 * Bulk hydrates the selected candidate window in one application-owned operation.
 *
 * @param <Q> query input type
 * @param <ID> canonical candidate identifier type
 */
@FunctionalInterface
public interface BatchDocumentMapper<Q, ID> {
  /**
   * Supplies provider text for every selected ID, without requiring a Kervan document schema.
   *
   * @param query application query
   * @param candidates immutable selected candidate window
   * @param context decreasing execution budget; callbacks must cooperate with it and interruption
   * @return exactly one non-null document string per candidate ID, with no additional IDs
   * @throws Exception if hydration or mapping fails
   */
  Map<ID, String> map(Q query, List<RankedCandidate<ID>> candidates, RankingContext context)
      throws Exception;
}
