# Extending Kervan

Application adapters implement the small public contracts in `kervan-api`.
`kervan-core` coordinates the adapters and enforces limits, deadlines, failures,
and final uniqueness. Keep provider-specific clients and request formats outside
the API and core modules.

## Custom retriever

Implement `CandidateRetriever<Q, ID>`. Its `retrieve` method receives an
application query and a `RetrievalContext` with a positive candidate limit and
remaining time budget. Return an ordered list of `SourceCandidate<ID>` values.
The first item is rank 1. Return an empty list for a successful empty response;
throw an exception for provider failure. Use the time budget in your native client
where possible, and return no more than the requested limit.

Map native IDs to the application's **canonical ID** before returning them. For
example, the [HTTP recommendation adapter](../examples/product-discovery/src/main/java/io/github/ahmetyasarkaplan/kervan/example/RecommendationHttpRetriever.java)
maps remote `camera-kit` to `catalog:camera-kit`, matching the
[Elasticsearch adapter bean](../examples/product-discovery/src/main/java/io/github/ahmetyasarkaplan/kervan/example/ProductDiscoveryConfiguration.java).
Without this mapping, Kervan cannot recognize the same product across providers.

Keep query text, credentials, raw responses, and sensitive IDs out of exception
messages. Kervan sanitizes provider failures in pipeline results but application
logs can still expose them. Avoid hidden retries that can outlast the time budget.

## Verify a retriever

Put `kervan-testkit` on your test classpath (`testImplementation` in Gradle).
Use `CandidateRetrieverContract<Q, ID>` with a deterministic fixture:

```java
class MyRetrieverContractTest implements CandidateRetrieverContract<String, String> {
  public CandidateRetriever<String, String> retriever() {
    return (query, context) -> List.of(SourceCandidate.of("catalog:camera-kit", 1.0));
  }

  public String query() { return "camera"; }

  public List<String> expectedCanonicalIds() {
    return List.of("catalog:camera-kit");
  }
}
```

Add imports for `CandidateRetriever`, `SourceCandidate`,
`CandidateRetrieverContract`, and `java.util.List`. The contract checks ordered
canonical IDs, the candidate limit, valid output, and repeatability. Use
`FailingCandidateRetrieverContract` for a deterministic failure fixture; add
focused tests for your real wire protocol and timeout behavior. The
[testkit guide](testkit.md) covers additional fixtures and assertions.

## Custom fusion strategy

Implement `FusionStrategy<ID>` to combine `List<RetrievalResult<ID>>` into an
ordered list of `RankedCandidate<ID>`. Read `FusionContext.candidateLimit()` and,
if relevant, `sourceWeights()`. Preserve each contributing provider's original
rank and score through `SourceContribution`. Return unique canonical IDs in
deterministic order; the pipeline rejects duplicate IDs and caps the output.
Start with `ReciprocalRankFusion` unless the sources' score scales are meaningful
after normalization. Configure a custom implementation with `.fusion(strategy,
limit)` or a named Spring bean with `fusion.type: custom`.

## Custom ranker

Implement `Ranker<Q, ID>` to reorder or rescore the fused candidates using the
query and `RankingContext.candidateLimit()`. Return unique canonical IDs, preserve
source contributions, and order ties deterministically. Do not fetch unbounded
data or ignore the deadline. Configure it with `.ranker(ranker, limit)` or a
named Spring bean with `ranker.type: custom`.

For a single scoring call over the fused window, use `BatchScoringRanker` and
an application-owned `BatchScorer`. Configure `RankerSpec` for input/output
limits, timeout and `KEEP_FUSED` or `FAIL` behavior. The callback can batch-load
typed application data and invoke a model with `context.remainingBudget()`.
The [batch reranking guide](reranking.md) describes exact score coverage,
stable ties, score provenance and explicit fallback.

## Custom ranking policy

Implement `RankingPolicy<Q, ID>` for final deterministic filtering or ordering
after the ranker. A policy receives the query, ranked candidates, and a
`RankingContext` whose limit is the final result size. It can remove candidates,
return an empty list, or reorder them. It must return each candidate at most once
and cannot introduce a canonical ID or change its score, attributes, or source
contributions. The pipeline checks that each returned candidate equals its
ranked input, then applies the final result limit. Keep the ranker limit high
enough to give a filtering policy alternatives to fill the result.

Configure a policy with `.policy(policy)` on the Java builder or a named Spring
bean with `policy.type: custom` and `policy.bean: myPolicy`. Without a policy,
the identity policy retains the ranker's order. Policy work shares the pipeline
deadline and is reported as a `POLICY` observation stage.

Built-in [pinning, group quotas and composition](ranking-policies.md) cover
common final adjustments while preserving candidate values and source data.

## Observability

`RankingPipeline.Builder.observer(...)` accepts one `PipelineObserver`; use
`CompositePipelineObserver` when combining metrics, tracing, and logs. The
[observability guide](observability.md) shows the optional module. Instrument
provider name, status, duration, and counts. Avoid query text, candidate IDs,
vectors, and user IDs in metric labels or default logs.

## Failure behavior

`PARTIAL_RESULTS` returns a partial result when an optional provider fails or
times out and another provider succeeds, including a successful empty list.
Required provider failure, or no usable providers, raises
`PipelineExecutionException`. `FAIL_FAST` raises on any failure or timeout and
cancels unfinished work. A global deadline also stops waiting. Provider
interruption is best effort, so adapters should use their native timeout and
cooperate with interruption. See the [architecture guide](architecture.md) for
the full status matrix and deadline grace behavior.
