# Changelog

## 1.0.0

- Java 21 pipeline with concurrent provider calls, global and provider deadlines,
  required and optional providers, explicit failure policies, candidate budgets,
  and source provenance.
- Reciprocal-rank, weighted reciprocal-rank, weighted min-max, and first-seen
  fusion; custom rankers and final filtering or ordering policies.
- Batch scoring with bounded input/output windows, shared remaining time,
  explicit fused fallback, ranking outcomes, and separate fusion/reranking scores.
- Per-source attribute preservation, existing-candidate pinning, group quotas,
  policy composition, and offline precision, recall, reciprocal-rank and nDCG metrics.
- Ready Cohere v2 batch scorer, OpenSearch and JDBC pgvector adapters, and
  maximal marginal relevance selection with preserved score provenance.
- Elasticsearch adapter for caller-defined native search requests, including
  hybrid query and kNN retrieval through the official Java client.
- Qdrant adapter for caller-defined vector and native hybrid queries, canonical
  IDs, explicit similarity or distance score direction, and bounded requests.
- Spring Boot starter with named pipeline configuration, application component
  beans, startup validation, lifecycle management, and optional health indicator.
- Optional metrics, tracing, structured logging, HTTP server adapter, and
  retriever testkit.
- Product-discovery example, Docker-backed Elasticsearch, OpenSearch, Qdrant and pgvector
  integration tests, independent starter and adapter published-artifact consumer checks,
  and Maven Central bundle verification.
