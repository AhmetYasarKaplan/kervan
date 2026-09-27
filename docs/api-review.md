# Public API review

This review covers the public contracts in `kervan-api`, the pipeline builder and
built-in strategies in `kervan-core`, and the entry points of the optional
Elasticsearch and Spring Boot modules. The runnable example uses these entry
points without package-private access or changes to the framework.

| Surface | Reviewed contract |
| --- | --- |
| `CandidateRetriever<Q, ID>` | Ordered canonical IDs, finite scores, positive candidate limit and time budget |
| `RankingPipeline<Q, ID>` | Named providers, explicit required/optional status, global timeout, failure policy, reusable execution and close lifecycle |
| `PipelineResult<ID>` | Immutable list snapshots, provider outcomes in registration order, partial-result metadata and preserved source contributions |
| `FusionStrategy<ID>` and `Ranker<Q, ID>` | Public extension points with output limits, deterministic order and unique canonical IDs |
| Elasticsearch adapter | Accepts a provider-native search request, maps hit IDs and leaves client ownership with the application |
| Spring Boot registry | Resolves configured pipelines by name and concrete query/ID type tokens; context owns lifecycle |

The review did not find a need to rename public types or add a general provider
query language. The example exercises the embedded Spring Boot path and
native Elasticsearch hybrid request with an independent HTTP provider.

Two usage details need to stay visible in the documentation:

- Collection values are immutable snapshots, but arbitrary candidate IDs and
  attribute values are not deep copied. Applications should use immutable values.
- Calling `execute` after a pipeline is already closed raises
  `IllegalStateException`; closing during an active call is reported through
  `PipelineExecutionException` with reason `CLOSED`. Remote work may continue
  after Kervan stops waiting if a provider ignores interruption.
