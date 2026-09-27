package io.github.ahmetyasarkaplan.kervan.api;

import java.util.List;

/**
 * Retrieves an ordered list of candidates from one independent provider.
 *
 * @param <Q> application query type
 * @param <ID> canonical candidate identifier type
 */
@FunctionalInterface
public interface CandidateRetriever<Q, ID> {

  /**
   * Returns candidates with canonical application IDs. The core measures duration and assigns
   * one-based source ranks from this list's positions.
   *
   * @param query the application query
   * @param context the maximum number of unique candidates requested from this provider
   * @return candidates in provider rank order
   */
  List<SourceCandidate<ID>> retrieve(Q query, RetrievalContext context);
}
