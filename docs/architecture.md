# Architecture

## Foundation

Kervan is designed as an embedded retrieval and ranking library structured
around strict module boundaries and clean separation of concerns.

```text
kervan-core → kervan-api → JDK
```

| Module | Package prefix | Responsibility |
| --- | --- | --- |
| `kervan-api` | `io.github.ahmetyasarkaplan.kervan.api` | Public contracts and domain-independent values |
| `kervan-core` | `io.github.ahmetyasarkaplan.kervan.core` | Pipeline construction and execution |

## Dependency rules

- API must not depend on core.
- Core exposes its API dependency through Gradle's `api` configuration because
  future engine signatures will use API types.
- Neither module may depend on Spring, Elasticsearch, HTTP frameworks, or
  infrastructure-specific serialization types or annotations.
- The core modules maintain zero external production dependencies. Test libraries and
  build tools are separate from the production dependency graph.
- Future infrastructure integrations belong in separate adapter modules that
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
disabled. Java source and tests will use the standard `src/main/java` and
`src/test/java` layouts when behavior is introduced.

## Pipeline lifecycle

The ranking pipeline coordinates candidates through explicit stages:

1. **Retrieval:** Concurrent fan-out to configured candidate sources.
2. **Deduplication:** Merging duplicate candidate identifiers while preserving source provenance and raw scores.
3. **Fusion:** Combining ranks and scores using deterministic algorithms such as Reciprocal Rank Fusion (RRF) or weighted score normalization.
4. **Ranking & Policies:** Applying downstream re-ranking models and business rules within latency and candidate budgets.
