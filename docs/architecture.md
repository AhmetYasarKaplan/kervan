# Architecture

## Foundation

Kervan is designed as an embedded retrieval and ranking library structured
around strict module boundaries and clean separation of concerns.

```text
kervan-core -> kervan-api -> JDK
```

| Module | Package prefix | Responsibility |
| --- | --- | --- |
| `kervan-api` | `io.github.ahmetyasarkaplan.kervan.api` | Public contracts and domain-independent values |
| `kervan-core` | `io.github.ahmetyasarkaplan.kervan.core` | Pipeline construction and execution |

## Dependency rules

- API must not depend on core.
- Core exposes its API dependency through Gradle's `api` configuration because
  its public component implementations use API types in their signatures.
- Neither module may depend on Spring, Elasticsearch, HTTP frameworks, or
  infrastructure-specific serialization types or annotations.
- The core modules maintain zero external production dependencies. Test libraries and
  build tools are separate from the production dependency graph.
- Infrastructure integrations belong in separate adapter modules that
  depend on these libraries. They must not introduce reverse dependencies.

Each module's `verifyModuleBoundaries` task checks its resolved compile and runtime
classpaths. The root `check` includes these checks, both modules' tests and coverage
tasks, and repository formatting. No Java module descriptors are introduced;
module boundaries currently refer to Gradle projects and Java packages.

## Package and API rules

Public contracts belong in the API package. Core owns the execution engine and
keeps implementation details package-private wherever possible. Exported types
must be deliberate; a public core implementation should not become an extension
contract accidentally.

Java 21 is the compile and test baseline. Preview language/runtime features are
disabled. Java source and tests use the standard `src/main/java` and
`src/test/java` layouts.

## Candidate and result invariants

`Candidate` requires a non-null ID and finite current and source scores. Negative
finite scores are valid. Collections are defensively copied and unmodifiable;
IDs and attribute values are not deep copied. Callers should use immutable values.
Set and map iteration order does not define candidate ranking.

`RetrievalResult` enforces the same invariants through its constructor and factory
methods:

| Status | Candidates | Meaning |
| --- | --- | --- |
| `SUCCESS` | At least one | Successful retrieval with results |
| `EMPTY` | None | Successful retrieval without results |
| `FAILED` | None | Retrieval failure |
| `TIMED_OUT` | None | Retrieval timeout |

Retriever names must be non-blank and durations non-negative. Failure information
is limited to status values. Results do not carry exception messages, causes, or
stack traces.

`PipelineResult` preserves candidate order and copies its collections. Successful
and failed stage sets must be disjoint and contain non-blank names. `isPartial`
must be true exactly when there are failed stages. Empty candidate lists are valid
for both complete and partial results; stage outcomes distinguish the two.

## Execution contracts

`Retriever`, `FusionStrategy`, and `Ranker` are synchronous, generic contracts.
They describe individual components rather than orchestrating a pipeline.
Their contexts require positive candidate limits. `RetrievalContext` accepts a
non-negative timeout budget, including zero; the context itself does not enforce
timing, schedule work, or cancel it.

`IdentityRanker` lives in core. It selects the leading candidates up to the ranking
limit without reordering or rescoring them, returning an unmodifiable snapshot.
Selected candidates must be non-null; the unused query may be null.
