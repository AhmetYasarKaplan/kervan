# ADR 0002: Framework-independent module boundaries

## Context

Kervan's embedded API must be usable without a particular application framework
or retrieval backend. Stable contracts and execution details have different
responsibilities and should remain independently understandable.

## Decision

Public contracts and values belong in `kervan-api`; pipeline construction and
execution belong in `kervan-core`. Core depends on API through Gradle's `api`
configuration. API never depends on core.

Use `io.github.ahmetyasarkaplan.kervan` as the Maven group and Java package prefix,
with `.api` and `.core` packages for the two modules. Both modules are Java
libraries with zero external production dependencies.

Spring, Elasticsearch, Qdrant, HTTP, and infrastructure-specific serialization
dependencies belong in optional adapters. Build checks enforce the production
dependency boundary without adding a separate architecture-testing library.

## Consequences

Integrations can evolve without becoming prerequisites for the embedded library.
Production dependency additions require an explicit boundary review.
Contracts and execution semantics are introduced alongside concrete behaviors,
avoiding empty placeholder abstractions.
