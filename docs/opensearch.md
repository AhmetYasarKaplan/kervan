# OpenSearch adapter

`kervan-opensearch` adapts the official `org.opensearch.client.opensearch.OpenSearchClient`.
The application owns client construction, credentials, transport configuration, indexes, documents,
embeddings, native queries, and canonical IDs. The optional module exposes the official Java client
on its API classpath and depends on `kervan-api` without introducing OpenSearch into core or starters.

```kotlin
implementation("io.github.ahmetyasarkaplan.kervan:kervan-opensearch:<kervan-version>")
```

Pass your existing official client and return a native `SearchRequest.Builder`:

```java
record Product(String canonicalId, String title) {}

var retriever = OpenSearchCandidateRetriever.<String, String, Product>builder(
        client, Product.class)
    .requestFactory((query, context) -> new SearchRequest.Builder()
        .index("products")
        .query(q -> q.match(m -> m.field("title").query(v -> v.stringValue(query)))))
    .idMapper(hit -> hit.source().canonicalId())
    .attributesMapper(hit -> Map.of("title", hit.source().title()))
    .scoreDirection(ScoreDirection.HIGHER_IS_BETTER)
    .build();

var candidates = retriever.retrieve("camera", RetrievalContext.of(100, Duration.ofMillis(500)));
```

Import adapter types from `io.github.ahmetyasarkaplan.kervan.opensearch`, API types from
`io.github.ahmetyasarkaplan.kervan.api`, and native `SearchRequest` from
`org.opensearch.client.opensearch.core`. `Void.class` and a native source-disabled request are useful
when the native hit ID already maps to your canonical identifier. Otherwise map the canonical
application ID from your document. Attributes are optional, copied into each candidate, and retained
in source contributions after fusion.

Use a native vector query in the same callback when appropriate:

```java
.requestFactory((query, context) -> new SearchRequest.Builder()
    .index("products")
    .query(q -> q.knn(k -> k.field("embedding")
        .vector(query.vector()).k(context.candidateLimit()))))
```

Your application query supplies the vector. OpenSearch's `knn_vector` index mappings and search
pipelines remain native configuration. Lexical, vector, filtered, or native hybrid searches each
produce one provider list; register an independent backend or recommendations source separately
for Kervan fusion. Shared canonical IDs combine contributions across providers.

For a native hybrid query, reference an application-configured normalization
search pipeline and supply both clauses in the same request:

```java
.requestFactory((query, context) -> new SearchRequest.Builder()
    .index("products")
    .searchPipeline("products-hybrid")
    .query(q -> q.hybrid(h -> h
        .queries(lexical -> lexical.match(m -> m.field("title")
            .query(v -> v.stringValue(query.text()))))
        .queries(vector -> vector.knn(k -> k.field("embedding")
            .vector(query.vector()).k(context.candidateLimit()))))))
```

Here the application query contains `text()` and `vector()`. Configure
`products-hybrid` in OpenSearch with its native normalization processor and
combination weights, following the
[OpenSearch hybrid search guide](https://docs.opensearch.org/latest/vector-search/ai-search/hybrid-search/index/).
OpenSearch performs this within-index combination; Kervan receives the resulting
ordered list as one source and can fuse it with other independent sources.

`ScoreDirection` is explicit. Ordinary OpenSearch relevance scores use `HIGHER_IS_BETTER`.
If your custom native score represents distance, use `LOWER_IS_BETTER`; the adapter negates the
numeric score while retaining native hit order. Configure the native request's ordering accordingly.
Missing/non-finite scores, null IDs, null attributes, or mapper failures become sanitized
`INVALID_RESPONSE` errors. Empty native results remain an empty provider list.

After your callback returns, the adapter overrides native `size`, applies a server timeout using the
remaining invocation budget, and disables partial search results. It also caps mapped hits. The
callback should return promptly and retain ownership of its index, filters, query, and search
pipeline configuration. The adapter shares your client's transport and transport options with an
asynchronous official client. It bounds its future wait by the remaining budget, requests future
cancellation on timeout/interruption, and restores the caller's interrupt flag. Cancellation is best
effort: some transports or remote operations continue after the wait ends. Configure connection and
response timeouts on the application-owned transport too. The adapter never closes the client or
transport. Close the pipeline first and your client/transport at the application's lifecycle boundary.

`OpenSearchRetrievalException.reason()` exposes safe categories for transport, remote, timeout,
request, response, and interruption failures. It does not retain native error bodies, query content,
credentials, or causes. A server timeout or failed shard response is a failure instead of a partial
successful candidate list; optional-provider pipeline policies still apply normally.

The module pins official Java client `3.10.0` and tests real lexical, vector, native hybrid queries
with a normalization search pipeline, and independent-source canonical fusion against
`opensearchproject/opensearch:3.3.2`. The Docker test
uses one node and disables security for local testing only. Application clients remain responsible
for production TLS and authentication. Run:

```powershell
.\gradlew.bat :kervan-opensearch:test
```

See the [official OpenSearch Java client documentation](https://docs.opensearch.org/latest/clients/java/)
for client transport setup and the [official client Maven publication](https://central.sonatype.com/artifact/org.opensearch.client/opensearch-java/3.10.0)
for the pinned dependency.
