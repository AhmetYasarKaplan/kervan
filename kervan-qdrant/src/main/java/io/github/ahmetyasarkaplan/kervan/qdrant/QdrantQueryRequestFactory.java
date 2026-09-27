package io.github.ahmetyasarkaplan.kervan.qdrant;

import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.qdrant.client.grpc.Points.QueryPoints;

/**
 * Builds a native Qdrant query, including optional filters, prefetches, or hybrid fusion.
 *
 * @param <Q> application query type
 */
@FunctionalInterface
public interface QdrantQueryRequestFactory<Q> {
  /**
   * Creates a new request builder for one invocation. The adapter overrides its top-level limit.
   *
   * @param query application query
   * @param context candidate limit and remaining time budget
   * @return a new native request builder
   */
  QueryPoints.Builder create(Q query, RetrievalContext context);
}
