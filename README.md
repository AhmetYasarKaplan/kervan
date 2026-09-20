# Kervan

**Bring candidates from many sources. Return one reliable ranking.**

Kervan is a Java 21 framework for latency-aware retrieval orchestration and rank
fusion. It is designed for applications that collect candidates from multiple
sources, combine their rankings, optionally rerank them, and apply result policies
within explicit latency and candidate budgets.

## Status

Kervan is under active development. The current development version is `0.1.0-SNAPSHOT`.
The implemented surface consists of candidate and result models, synchronous
component contracts, and an identity ranker. Pipeline execution is not implemented.

## Intended request flow

```text
Request
  -> parallel candidate retrieval
  -> deduplication and provenance
  -> rank/score fusion
  -> optional reranking and result policies
  -> final ranked results
```

The embedded Java library is the primary design target.

Kervan coordinates retrieval and ranking components. Search indexing, embedding
generation, model training/serving, user-profile services, and large relevance
experimentation platforms are outside its scope.

## Modules

| Module | Responsibility |
| --- | --- |
| `kervan-api` | Public contracts and domain-independent value types |
| `kervan-core` | Built-in execution components, including `IdentityRanker` |

Production dependencies flow from `kervan-core` to `kervan-api` to the JDK.
Neither module has external production dependencies. The Maven group and Java
package prefix are `io.github.ahmetyasarkaplan.kervan`.

## Build locally

Install a JDK 21 and make it available through `JAVA_HOME` or `PATH`. The build
uses a Java 21 toolchain and does not download JDKs automatically. Git and network
access to the Gradle distribution, Plugin Portal, and Maven Central are needed
for an initial checkout and build. A separate Gradle installation is unnecessary.

```sh
java -version
./gradlew --version
./gradlew clean check
```

On Windows, use `gradlew.bat clean check`.

The wrapper downloads the pinned Gradle distribution and verifies its SHA-256.
`check` runs formatting checks, module tests, coverage reporting, and production
dependency-boundary validation. GitHub Actions uses the same verification command
on Ubuntu with Temurin Java 21.

## Development checks

```sh
./gradlew spotlessCheck
./gradlew spotlessApply
./gradlew :kervan-api:check :kervan-core:check
```

Spotless formats Java with google-java-format and Kotlin build scripts with ktfmt.
JUnit 5 and AssertJ are configured for each module. Compiler warnings are enabled.
Tool versions are pinned in the [version catalog](gradle/libs.versions.toml) and
[wrapper configuration](gradle/wrapper/gradle-wrapper.properties).

Test and coverage reports are generated under each module:

- Tests: `build/reports/tests/test/index.html`
- Coverage: `build/reports/jacoco/test/html/index.html`
- Coverage XML: `build/reports/jacoco/test/jacocoTestReport.xml`

## Design documentation

- [Architecture and module boundaries](docs/architecture.md)
- [Java 21 and Gradle](docs/adr/0001-java-21-and-gradle.md)
- [Framework-independent module boundaries](docs/adr/0002-module-boundaries.md)
