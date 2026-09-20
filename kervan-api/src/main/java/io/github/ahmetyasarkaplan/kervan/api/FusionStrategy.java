package io.github.ahmetyasarkaplan.kervan.api;

import java.util.List;

/**
 * Strategy contract for combining multiple candidate sets into a unified, ranked candidate list.
 *
 * @param <ID> the candidate identifier type.
 */
@FunctionalInterface
public interface FusionStrategy<ID> {

  /**
   * Fuses candidate results from multiple retrieval stages into a single ranked list.
   *
   * @param results list of candidate retrieval results from all executed retrievers.
   * @param context fusion execution settings including output candidate limits.
   * @return a deterministically ranked list of candidates.
   */
  List<Candidate<ID>> fuse(List<RetrievalResult<ID>> results, FusionContext context);
}
