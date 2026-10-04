# Public API review

This review covers the public contracts in `kervan-api`, the pipeline builder and
built-in strategies in `kervan-core`, and the entry points of the optional
Elasticsearch, Qdrant, Spring Boot, HTTP, observability, and testkit modules.
Examples and adapter tests use these entry points without package-private access
or changes to the framework.

| Surface | Reviewed contract |
| --- | --- |
| `CandidateRetriever<Q, ID>` | Ordered canonical IDs, finite higher-is-better scores, positive candidate limit and time budget |
| `ScoreDirection` | Preserves similarity scores or negates native distances before score-based fusion; never reorders the provider list |
| `RankingPipeline<Q, ID>` | Named providers, explicit required/optional status, global timeout, failure policy, reusable execution and close lifecycle |
| `PipelineResult<ID>` | Immutable list snapshots, provider outcomes in registration order, partial-result metadata and preserved source contributions |
| `FusionStrategy<ID>` and `Ranker<Q, ID>` | Public extension points with output limits, deterministic order and unique canonical IDs |
| `RankingPolicy<Q, ID>` | Final filtering and reordering of existing ranked candidates, with unchanged candidate values and provenance |
| Elasticsearch adapter | Accepts a provider-native search request, maps hit IDs and leaves client ownership with the application |
| Qdrant adapter | Accepts a native query including dense/sparse prefetch fusion, bounds its top-level limit and wait budget, requires explicit score direction and leaves client ownership with the application |
| Spring Boot registry | Resolves configured pipelines by name and concrete query/ID type tokens; context owns lifecycle |
| HTTP binding | Explicitly allows a configured pipeline and concrete query/ID types to be invoked through the optional server adapter |
| `PipelineObserver` and observability adapters | Receive configured names, durations, coarse statuses and counts without query or candidate contents; telemetry remains optional |
| Testkit contracts | Exercise ordered candidates, limits, canonical IDs, finite scores and sanitized failures through public retriever implementations |

Provider queries remain native to each adapter. The product discovery example
exercises the embedded Spring Boot path and a native Elasticsearch hybrid request
with an independent HTTP provider. Qdrant integration tests exercise native
dense/sparse fusion as one provider result combined with a recommendation source.
The optional server adapter delegates to the same registry and does not add a
second execution path for ranking behavior.

Contract details:

- Collection values are immutable snapshots, but arbitrary candidate IDs and
  attribute values are not deep copied. Applications should use immutable values.
- `SourceContribution.score()` holds the Kervan-facing value. When an adapter
  converts a distance, applications can preserve the native value separately in
  a namespaced candidate attribute if needed.
- Calling `execute` after a pipeline is already closed raises
  `IllegalStateException`; closing during an active call is reported through
  `PipelineExecutionException` with reason `CLOSED`. Remote work may continue
  after Kervan stops waiting if a provider ignores interruption.
- The Qdrant adapter requests cancellation of its pending client future on timeout
  or interruption and preserves the worker's interrupt flag. This is best effort;
  it does not guarantee that remote server work has stopped.
