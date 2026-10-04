# ADR 0003: Coordinate independent providers and push down native search work

## Context

Search engines can perform lexical retrieval, vector retrieval, hybrid fusion,
and reranking inside their own document universe. Applications may also need to
combine those results with independently operated recommendation, popularity,
or rules providers. These providers have separate latency, availability, and
candidate identity concerns.

## Decision

Kervan coordinates independent provider calls and combines their results by
canonical application ID. An adapter should push an operation into its provider
when the provider performs it natively, correctly, and more efficiently. An
Elasticsearch native hybrid search is one Kervan provider result. The application
or adapter maps provider-native IDs into canonical IDs before fusion.

## Consequences

Core remains independent of provider clients and query languages. Cross-provider
fusion preserves each provider's returned rank and Kervan-facing score, but does
not claim access to the provider's internal subquery contributions unless the
provider exposes them. Adapters orient native distances into higher-is-better
scores before score-based fusion. Provider-specific request construction stays
in adapter modules.
