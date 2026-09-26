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
kervan-core -> kervan-api -> JDK
```

| Module | Package prefix | Responsibility |
| --- | --- | --- |
| `kervan-api` | `io.github.ahmetyasarkaplan.kervan.api` | Public component contracts and domain-independent values |
| `kervan-core` | `io.github.ahmetyasarkaplan.kervan.core` | Pipeline construction and execution, built-in components |

API does not depend on core. Neither module depends on Spring, Elasticsearch,
HTTP frameworks, or infrastructure-specific serialization annotations. Core
exposes API through Gradle's `api` configuration because public core components
use API types in their signatures. The `verifyModuleBoundaries` task validates
resolved production classpaths; root `check` also runs tests and formatting.

## Candidate model

`CandidateRetriever<Q, ID>` returns an ordered `List<SourceCandidate<ID>>` for one
provider. Each source candidate has a canonical ID, a finite score, and small
optional attributes. The retriever does not set its name, measured duration, or
execution status. Its `RetrievalContext` carries a positive requested candidate
limit.

The core assigns a one-based `SourceHit.rank` from each candidate's original list
position. Within one provider list, the first occurrence of a canonical ID wins.
The limit applies to unique IDs after duplicate removal; original ranks retain
their positions, so rank numbers can have gaps. The core validates the full list,
then retains at most the configured number of unique candidates.

`RetrievalResult` contains the provider name, source hits, measured duration, and
status. A successful nonempty result is `SUCCESS`; a successful empty result is
`EMPTY`. Other status values exist in the model but are not produced by the
sequential pipeline. Provider exceptions currently propagate to the caller.

`RankedCandidate` represents the result after cross-provider fusion. It contains
the canonical ID, current score, and ordered `SourceContribution` values holding
each provider's original rank and score. The final rank is the candidate's
one-based position in the result list. Candidate and result collections are
unmodifiable snapshots; IDs and attribute values are not deep copied, so callers
should use immutable values.

## Sequential execution

```text
provider list in registration order
  -> validate, remove within-provider duplicates, and cap unique candidates
  -> create core-measured retrieval results
  -> fuse equal canonical IDs across providers
  -> rank and apply the final candidate limit
  -> pipeline result
```

`RankingPipeline` requires at least one named retriever, an explicit fusion
strategy, an explicit ranker, and a positive result limit. Retriever names must
be unique. It invokes retrievers in registration order and passes their completed
results to fusion in that same order. Later stage outputs are capped to their
configured limits, and duplicate IDs returned by custom fusion or ranker stages
are rejected.

`FirstSeenFusion` is the baseline strategy: the first provider occurrence
determines final position and score. Contributions from later providers are
appended in provider order, and attributes from the first occurrence of each key
win. `IdentityRanker` preserves this order while applying its candidate limit.
This baseline does not calculate a relevance-fusion score.

A successful sequential execution returns a non-partial `PipelineResult` with
successful provider names and total duration. It does not run providers in
parallel or enforce deadlines or failure policies. `PipelineResult` validates
that successful and failed stage sets do not overlap and that `isPartial` agrees
with the presence of failed stages. No raw exception information is stored in
the result.
