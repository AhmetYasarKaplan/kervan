# Changelog

## 1.0.0

- Java 21 pipeline with concurrent provider calls, global and provider deadlines,
  required and optional providers, explicit failure policies, candidate budgets,
  and source provenance.
- Reciprocal-rank, weighted reciprocal-rank, weighted min-max, and first-seen
  fusion; custom rankers and final filtering or ordering policies.
- Elasticsearch adapter for caller-defined native search requests, including
  hybrid query and kNN retrieval through the official Java client.
- Qdrant adapter for caller-defined vector and native hybrid queries, canonical
  IDs, explicit similarity or distance score direction, and bounded requests.
- Spring Boot starter with named pipeline configuration, application component
  beans, startup validation, lifecycle management, and optional health indicator.
- Optional metrics, tracing, structured logging, HTTP server adapter, and
  retriever testkit.
- Product-discovery example, Docker-backed Elasticsearch and Qdrant integration tests,
  independent published-artifact consumer check, and Maven Central bundle
  verification.
