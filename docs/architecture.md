# Architecture

## Provider boundary

Kervan coordinates independently invoked candidate providers at the application
boundary. A search engine owns retrieval and ranking operations inside its own
index or document universe. Its adapter can send one native hybrid search request
and return the ordered hits as one provider list. Kervan can combine that list with
recommendation, popularity, or other independent provider lists.

Provider adapters map native document or item identifiers into the application's
canonical candidate ID before returning candidates. The core compares those IDs;
it does not contain application-specific identity translation. The application
also owns hydration of full domain entities after receiving ranked IDs.

Where a provider can perform an operation natively and efficiently, its adapter
should request that behavior from the provider. Kervan does not define a universal
provider query language. See the [provider-boundary decision](adr/0003-provider-boundaries.md).

## Module boundaries

```text
kervan-core          -> kervan-api -> JDK
kervan-elasticsearch -> kervan-api
                     -> Elasticsearch Java client
```

| Module | Package prefix | Responsibility |
| --- | --- | --- |
| `kervan-api` | `io.github.ahmetyasarkaplan.kervan.api` | Public component contracts and domain-independent values |
| `kervan-core` | `io.github.ahmetyasarkaplan.kervan.core` | Pipeline construction and execution, built-in components |
| `kervan-elasticsearch` | `io.github.ahmetyasarkaplan.kervan.elasticsearch` | Native search request adapter and canonical hit mapping |

API does not depend on core. Neither API nor core depends on Spring, Elasticsearch,
HTTP frameworks, or infrastructure-specific serialization annotations. Core
exposes API through Gradle's `api` configuration because public core components
use API types in their signatures. The `verifyModuleBoundaries` task validates
resolved production classpaths; root `check` also runs tests and formatting.

The Elasticsearch adapter accepts a provider-native `SearchRequest.Builder`
from application code, applies the candidate limit and server-side timeout,
then maps ordered hits to canonical IDs. Its client dependency stays outside
API and core. One native Elasticsearch hybrid response is one Kervan provider
list; Kervan's fusion combines that list with other independent providers.

## Candidate model

`CandidateRetriever<Q, ID>` returns an ordered `List<SourceCandidate<ID>>` for one
provider. Each source candidate has a canonical ID, a finite score, and small
optional attributes. The retriever does not set its name, measured duration, or
execution status. Its `RetrievalContext` carries a positive requested candidate
limit and the smaller remaining per-provider and global time budgets.

The core assigns a one-based `SourceHit.rank` from each candidate's original list
position. Within one provider list, the first occurrence of a canonical ID wins.
The limit applies to unique IDs after duplicate removal; original ranks retain
their positions, so rank numbers can have gaps. The core validates the full list,
then retains at most the configured number of unique candidates.

`RetrievalResult` contains the provider name, source hits, measured duration, and
status. A successful nonempty result is `SUCCESS`; a successful empty result is
`EMPTY`. Provider exceptions become `FAILED` without retaining their messages.
The core separately classifies per-provider `TIMED_OUT`, global
`DEADLINE_EXCEEDED`, and work `CANCELLED` after a fatal outcome or closure.

`RankedCandidate` represents the result after cross-provider fusion. It contains
the canonical ID, current score, and ordered `SourceContribution` values holding
each provider's original rank and score. The final rank is the candidate's
one-based position in the result list. Candidate and result collections are
unmodifiable snapshots; IDs and attribute values are not deep copied, so callers
should use immutable values.

## Execution and failure handling

```text
provider list fanned out concurrently on virtual threads
  -> validate, remove within-provider duplicates, and cap unique candidates
  -> create core-measured retrieval results
  -> collect outcomes in registration order
  -> fuse equal canonical IDs across providers
  -> rank and apply the final candidate limit
  -> pipeline result
```

`RankingPipeline` requires at least one named retriever, a ranker, positive
result limit, global timeout, and failure policy. Without an explicit fusion
strategy, it uses reciprocal rank fusion with `k = 60` and a fusion limit equal
to the sum of configured provider candidate limits, capped at the maximum
integer value.
Retriever names must be unique. Each retriever specifies a positive timeout and
whether it is required. Calls run concurrently, while fusion receives outcomes in
registration order. Later stage outputs are capped to their configured limits;
duplicate IDs returned by custom fusion or ranker stages fail the pipeline.

`FirstSeenFusion` is the baseline strategy: the first provider occurrence
determines final position and score. Contributions from later providers are
appended in provider order, and attributes from the first occurrence of each key
win. `IdentityRanker` preserves this order while applying its candidate limit.
This baseline does not calculate a relevance-fusion score.

`ReciprocalRankFusion` sums `1 / (k + source rank)` for each contribution, where
`k` is a positive rank constant. It uses source order rather than raw scores, so
providers may use unrelated scoring scales. `WeightedMinMaxFusion` normalizes
scores separately within each provider's returned, deduplicated window, then
sums weighted values. A source with equal scores assigns 1.0 to every returned
candidate. Weights are positive finite values on `RetrieverSpec`; the default is
1.0. Reciprocal rank fusion ignores weights. Both strategies sort by fused score,
then best source rank, then ranks in provider registration order, then first
appearance. They cap their output at the configured fusion limit while retaining
original source contributions. The provider candidate limit is applied before
fusion, so it controls which candidates contribute; min-max scores also depend
on the returned window. See the [fusion decision](adr/0005-cross-provider-fusion.md).

A pipeline owns one reusable virtual-thread executor, accepts concurrent
`execute` calls, and must be closed when it is no longer needed. Closing stops
new calls and interrupts in-flight work. Interruption is best effort: a provider
or remote server may continue running after Kervan stops waiting.

Under `PARTIAL_RESULTS`, optional failure or timeout produces a partial result
when at least one provider completed successfully, including an empty success.
Required failure or timeout, or total provider failure, throws
`PipelineExecutionException`. Under `FAIL_FAST`, any failure or timeout throws and
remaining work is cancelled. An interrupted caller and a closed pipeline also
stop execution. The exception reports sanitized reasons and per-provider status;
it does not include provider messages. A successful `PipelineResult` exposes
provider outcomes, duration, derived success/failure/timeout names, and whether
it is partial.

| Policy | Provider outcome | Pipeline outcome |
| --- | --- | --- |
| `PARTIAL_RESULTS` | Optional failure or timeout, with another successful or empty provider | Partial result |
| `PARTIAL_RESULTS` | Required failure or timeout | `REQUIRED_RETRIEVER_FAILED` |
| `PARTIAL_RESULTS` | All providers fail or time out | `ALL_RETRIEVERS_FAILED` |
| `FAIL_FAST` | Any provider fails or times out | `FAIL_FAST`; unfinished work is cancelled |
| Either | All providers succeed, including empty results | Complete result |

The global deadline stops waiting for unfinished providers. A finalization grace
of at most 25 ms, also capped at one quarter of the configured timeout, allows
already completed results to be fused and ranked. Custom fusion or ranking code
that exceeds this bounded interval is cancelled and the pipeline fails with
`GLOBAL_DEADLINE_EXCEEDED`. This grace means total elapsed time can slightly
exceed the configured timeout.
