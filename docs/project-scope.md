# Product scope

Kervan is a Java 21 library that coordinates independently invoked candidate
providers and returns one deterministic ranking with source contributions and
execution outcomes. Embedded use is its primary boundary; Spring Boot
configuration, telemetry, and HTTP exposure are optional integrations.

## Responsibilities

- Invoke independent providers concurrently with global and per-provider time
  budgets, required/optional semantics, and explicit failure policies.
- Validate and bound candidate lists, merge equal canonical application IDs,
  and preserve provider order, ranks, and Kervan-facing scores.
- Fuse results across providers using reciprocal rank, weighted reciprocal rank,
  weighted min-max, or first-seen ordering. Apply application rankers and final
  filtering or ordering policies within defined candidate limits.
- Score a bounded fused window through an application-owned batch scorer, with
  a remaining time budget, explicit fused fallback and inspectable ranking outcomes.
- Preserve source attributes and separate fusion/reranking scores; provide
  deterministic existing-candidate pins and group quotas for final results.
- Report successful, empty, failed, timed-out, deadline-exhausted, and cancelled
  provider outcomes without retaining remote error details.
- Provide optional Elasticsearch, Qdrant, OpenSearch and JDBC pgvector adapters,
  a Cohere batch scorer, Spring Boot assembly, instrumentation, an HTTP binding,
  retriever test tools and offline relevance metrics.
- Select a diverse candidate order with maximal marginal relevance using
  application-supplied relevance and similarity functions.

API and core have no external production dependencies. Provider clients,
frameworks, and serialization belong in optional modules. Public contracts stay
generic over application queries and canonical ID types.

## Provider and application ownership

An Elasticsearch native hybrid request or a Qdrant dense/sparse fusion query is
one provider invocation. Index-local retrieval, filtering, and fusion remain
inside the provider. Kervan combines that ordered result with independently
operated recommendation, popularity, rules, or other candidate providers.

Applications own native request construction, canonical ID mapping, client
lifecycles, query embeddings, and hydration of domain entities. Retrievers return
finite higher-is-better scores. Distance-based adapters orient native values
before score-based fusion; rank-based fusion uses returned positions.

Cancellation is best effort. A pipeline stops waiting within its documented
deadline and finalization rules, but a remote provider may continue its work.
See [architecture](architecture.md) for execution and failure semantics and
[public API contracts](api-review.md) for extension boundaries.

## Outside the boundary

Kervan does not own search indexing, embedding generation, model training or
serving, user-profile services, feature stores, event ingestion, experimentation
platforms, or domain entity storage. It does not reimplement search-engine hybrid
internals, define a universal provider query language, or perform automatic
provider capability negotiation or cost-based query planning.

The product discovery application demonstrates the library's public extension
path. Its catalog and HTTP recommendation fixture are examples rather than
services that Kervan applications must adopt.
