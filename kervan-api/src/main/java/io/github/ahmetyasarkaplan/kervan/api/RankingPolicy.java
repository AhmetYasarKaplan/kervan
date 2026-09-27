package io.github.ahmetyasarkaplan.kervan.api;

import java.util.List;

/**
 * Final deterministic filtering or ordering of already ranked candidates. A policy may omit or
 * reorder candidates, but must not add IDs or change candidate scores, attributes, or source
 * contributions. The pipeline validates this contract and applies the final result limit.
 *
 * @param <Q> application query type
 * @param <ID> canonical candidate identifier type
 */
@FunctionalInterface
public interface RankingPolicy<Q, ID> {

  /**
   * Applies a final policy to ranked candidates. An empty result is valid.
   *
   * @param query application query
   * @param candidates ordered ranked inputs
   * @param context maximum final candidate count
   * @return unique subset of the inputs, in final order
   */
  List<RankedCandidate<ID>> apply(
      Q query, List<RankedCandidate<ID>> candidates, RankingContext context);
}
