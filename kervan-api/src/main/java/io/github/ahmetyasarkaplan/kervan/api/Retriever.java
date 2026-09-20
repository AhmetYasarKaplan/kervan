package io.github.ahmetyasarkaplan.kervan.api;

/**
 * Functional contract for candidate retrieval sources.
 *
 * @param <Q> the query input type.
 * @param <ID> the candidate identifier type.
 */
@FunctionalInterface
public interface Retriever<Q, ID> {

  /**
   * Retrieves candidates matching the given query within the provided context.
   *
   * @param query the search or recommendation query input.
   * @param context execution parameters such as candidate limits and timeouts.
   * @return the retrieval result containing candidates and execution status.
   */
  RetrievalResult<ID> retrieve(Q query, RetrievalContext context);
}
