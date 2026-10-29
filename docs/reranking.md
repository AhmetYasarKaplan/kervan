# Batch reranking

Fusion combines provider ranks or scores. A reranker can then evaluate the fused
candidates together against the query, using application features or a model.
Kervan supplies the batch bridge, limits, timeout, fallback and score provenance.
The application owns its data lookup, scoring logic and model/client lifecycle.

## One scoring call for the fused window

```java
import io.github.ahmetyasarkaplan.kervan.api.RankerFailurePolicy;
import io.github.ahmetyasarkaplan.kervan.core.BatchScoringRanker;
import io.github.ahmetyasarkaplan.kervan.core.RankerSpec;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

var titles = Map.of("camera", "camera kit", "tripod", "camera tripod");
var ranker = new BatchScoringRanker<String, String>(
    (query, candidates, context) -> candidates.stream().collect(Collectors.toMap(
        candidate -> candidate.id(),
        candidate -> titles.get(candidate.id()).contains(query) ? 1.0 : 0.0)));

// Attach to an otherwise configured RankingPipeline.Builder<String, String>.
builder.ranker(new RankerSpec<>(
    ranker, 50, 10, Duration.ofMillis(100), RankerFailurePolicy.KEEP_FUSED));
```

This callback demonstrates a local rule, not a semantic model. For an external
scorer, batch-load the selected IDs once, build the provider's native request,
and return one score for every ID. `BatchScorer<Q, ID>` permits checked exceptions
so a native client call can live directly inside the callback. No common document
schema is imposed on application data.

The input list is an immutable snapshot of the best fused candidates, capped by
`inputLimit`. The scorer is called once for a nonempty window, and never for an
empty window. Its map must cover exactly the input IDs with finite,
higher-is-better scores. Missing IDs, unknown IDs, null values and nonfinite
values fail ranking. Negative scores are valid. Ties retain input order without
requiring comparable IDs. Output is capped by `outputLimit` only after scoring;
the unscored tail is excluded. `outputLimit` must not exceed `inputLimit`.

## Time budgets and fallback

The pipeline owns the worker and cancellation. The effective ranking budget is
the smaller of `RankerSpec.timeout` and the original pipeline time remaining.
Inside a scorer, `context.remainingBudget()` decreases as hydration and scoring
take time. Read it again after hydration before passing a timeout to a model
client; do not restart the initial budget. Do not send a remote request when the
remaining budget is zero. Neither the scorer nor its client is closed by Kervan.

`KEEP_FUSED` returns the original fusion ordering and scores, capped by the ranking
output limit, if scoring fails, times out or cannot start before the deadline.
The final policy still runs afterward. `FAIL` fails the pipeline instead. These
settings are independent of the retriever failure policy. No retry is performed.

Caller interruption, pipeline close and fatal errors never become successful
fallback. Cancellation is best effort; an uncooperative remote operation may
continue after the pipeline stops waiting. The small finalization grace can
assemble fallback results and apply a local policy, but it is never given to a
new bounded scoring call. If finalization itself exhausts its budget, the entire
pipeline still fails.

`result.rankingOutcome()` exposes `SUCCESS`, `EMPTY`, `FAILED`, `TIMED_OUT`, or
`SKIPPED_NO_BUDGET`, plus counts and duration. Failure/skip outcomes on a successful
result mean fallback was used, also available through `fallbackUsed()`. The
ranking output count is zero on fallback; `result.candidates().size()` gives the
actual final result size. `NOT_REPORTED` is used by the legacy four-argument
`PipelineResult` constructor. Raw scorer exception details are not retained.

`isPartial()` continues to describe provider failures. `isDegraded()` includes
either provider partial results or ranking fallback. A successful fallback can
therefore have `isPartial() == false` and `isDegraded() == true`.

The existing `builder.ranker(ranker, limit)` path retains its finalization-bound
behavior. Use `RankerSpec` for an independent ranking budget and fallback. The
one-argument `RankingContext` constructor does not impose a stage timeout.

## Inspect scores and source features

`RankedCandidate.score()` is the score used for the current ranking. After batch
scoring, `rerankingScore()` contains both the original `fusionScore` and current
`rerankScore`. Their scales are separate and are never added together. Repeated
batch scoring retains the original fusion score. Fallback returns the unchanged
fused candidates without newly created reranking metadata.

Each `SourceContribution` retains its own rank, score and attribute map. For
example, two sources may both return `quality` without losing either value:
inspect the attribute map on the contribution named for that source. The merged
top-level attribute map retains its original first-source-wins behavior.
Attribute maps are immutable snapshots; arbitrary values are not deep copied.

## Run a complete example

```sh
./gradlew :examples:product-discovery:runReranking
```

This runs without external services. Two candidate providers are fused; one
batch catalog lookup and a query-token scoring rule move `catalog:tripod` ahead
of `catalog:camera-kit` for `camera tripod`. The output shows both scores and the
ranking outcome. The Docker-backed product-discovery application uses the same
scorer after Elasticsearch, HTTP recommendations and popularity retrieval.

The example test compares fusion and reranking using explicitly judged products
and the [relevance toolkit](testkit.md). That fixture verifies the integration;
it does not establish model quality on real traffic. Evaluate a representative
set of judged queries before selecting a scoring model or fusion configuration.

See [Spring configuration](spring-boot.md) for configuring the same limits in YAML.

For a ready external model connection, use the optional
[Cohere v2 scorer](cohere-reranking.md). For similarity-based diversification,
use [MMR](diversity.md). Use the [testkit's relevance metrics](testkit.md)
to compare configurations against application-owned relevance judgments.
