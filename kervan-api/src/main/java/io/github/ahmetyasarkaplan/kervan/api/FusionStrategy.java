package io.github.ahmetyasarkaplan.kervan.api;

import java.util.List;

/**
 * Combines results from independent providers into a list of ranked canonical candidates.
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
   * @return a deterministically ranked list of candidates with source contributions.
   */
  List<RankedCandidate<ID>> fuse(List<RetrievalResult<ID>> results, FusionContext context);
}
