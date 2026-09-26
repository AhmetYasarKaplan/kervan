package io.github.ahmetyasarkaplan.kervan.elasticsearch;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;

/** Builds one provider-native Elasticsearch search request for an application query. */
@FunctionalInterface
public interface ElasticsearchSearchRequestFactory<Q> {

  /**
   * Returns a request builder with the index and search operation configured. The adapter applies
   * the Kervan candidate limit and server-side timeout before sending it.
   */
  SearchRequest.Builder create(Q query, RetrievalContext context);
}
