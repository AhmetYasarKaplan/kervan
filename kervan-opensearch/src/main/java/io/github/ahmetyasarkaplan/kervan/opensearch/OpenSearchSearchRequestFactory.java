package io.github.ahmetyasarkaplan.kervan.opensearch;

import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import org.opensearch.client.opensearch.core.SearchRequest;

/**
 * Builds a native OpenSearch request with application-owned index, filters, and query.
 *
 * @param <Q> application query type
 */
@FunctionalInterface
public interface OpenSearchSearchRequestFactory<Q> {
  /**
   * Supplies the native request builder. The adapter applies size and server timeout afterward.
   *
   * @param query application query
   * @param context invocation limits
   * @return provider-native builder
   */
  SearchRequest.Builder create(Q query, RetrievalContext context);
}
