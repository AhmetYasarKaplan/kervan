# Elasticsearch adapter boundary

## Decision

`kervan-elasticsearch` uses the official Elasticsearch Java client 9.5.0 and
tests against the matching server version. Application code supplies a native
`SearchRequest.Builder` for each query and a mapper from an Elasticsearch hit
to the application's canonical candidate ID. The adapter sets the requested
top-K size and a server-side timeout from Kervan's retrieval context, rejects
partial shard results, executes one search, and returns its ordered hits as one
provider list.

The request factory can use Elasticsearch lexical, vector, hybrid, or other
supported search features without Kervan introducing a second query language.
The integration example combines `query` and `knn` in one Elasticsearch request.
Kervan does not recreate engine-local hybrid ranking. External recommendation
or other independently operated providers are fused across the provider
boundary.

Elasticsearch transport and response failures become a sanitized adapter
exception. The core records that exception as a provider failure according to
the configured pipeline policy. The server-side timeout and core deadline are
best-effort limits; interrupting a local call cannot guarantee that remote
server work stops. Applications own and close the Elasticsearch client.

## Consequences

Elasticsearch types are visible only in the adapter's public API. API and core
remain framework independent. The adapter targets the pinned client and server
version; compatibility with other server versions requires separate testing.
The request factory is responsible for index selection, native query design,
and source filtering. It should avoid returning full documents unless hit
mapping requires them.
