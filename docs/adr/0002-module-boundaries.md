# ADR 0002: Framework-independent module boundaries

Status: Accepted

## Context

Kervan's embedded API must be usable without a particular application framework
or retrieval backend. Stable contracts and execution details have different
responsibilities and should remain independently understandable.

## Decision

Start with `kervan-api` and `kervan-core`. Public contracts and values belong in
API; pipeline construction and execution belong in core. Core depends on API
through Gradle's `api` configuration. API never depends on core.

Use `io.github.ahmetyasarkaplan.kervan` as the Maven group and Java package prefix,
with `.api` and `.core` packages for the two modules. Both modules are Java
libraries with no external production dependencies at this stage.

Spring, Elasticsearch, HTTP, and infrastructure-specific serialization dependencies
belong in future adapters. Build checks enforce the current production dependency
boundary without adding a separate architecture-testing library.

## Consequences

Integrations can evolve without becoming prerequisites for the embedded library.
Future production dependency additions require an explicit boundary review.
Public types, method signatures, and execution semantics are decided with the
first implementation; empty placeholder contracts are unnecessary.
