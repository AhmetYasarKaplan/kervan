# Kervan

**Bring candidates from many sources. Return one reliable ranking.**

Kervan is a Java 21 library for coordinating candidates from independently
operated providers. An application can combine search, recommendation, popularity,
or custom provider results into one ranked list with canonical IDs and inspectable
source contributions.

## Status

Kervan is under active development. The current development version is
`0.1.0-SNAPSHOT`. The library provides a synchronous pipeline with concurrent
provider calls, time budgets, failure policies, reciprocal rank and weighted
score fusion, an Elasticsearch adapter, an identity ranker, candidate limits,
and source provenance. The public API is still subject to change.

## Intended request flow

```text
Request
  -> invoke independent providers concurrently within a deadline
  -> validate and limit each ordered source list
  -> classify provider success, failure, and timeout
  -> merge canonical IDs with source ranks and scores
  -> rank and limit candidates
  -> final ranked results
```

The embedded Java library is the primary design target.

Kervan operates across provider boundaries. A search engine can perform its own
lexical, vector, and hybrid retrieval inside one provider request; the application
can then combine that result with independently operated providers. Search indexing,
embedding generation, and model training/serving are outside Kervan's scope.

## Embedded usage

```java
import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.core.IdentityRanker;
import io.github.ahmetyasarkaplan.kervan.core.RankingPipeline;
import io.github.ahmetyasarkaplan.kervan.core.ReciprocalRankFusion;
import io.github.ahmetyasarkaplan.kervan.core.RetrieverSpec;
import java.time.Duration;
import java.util.List;

CandidateRetriever<String, String> search =
    (query, context) -> List.of(SourceCandidate.of("product-1", 4.2));
CandidateRetriever<String, String> recommendations =
    (query, context) -> List.of(SourceCandidate.of("product-1", 0.8));

try (RankingPipeline<String, String> pipeline =
    RankingPipeline.<String, String>builder("discovery")
        .addRetriever(RetrieverSpec.required("search", search, 10, Duration.ofMillis(140)))
        .addRetriever(
            RetrieverSpec.optional("recommendations", recommendations, 10, Duration.ofMillis(100)))
        .fusion(new ReciprocalRankFusion<>(60), 20)
        .ranker(new IdentityRanker<>(), 20)
        .resultLimit(10)
        .timeout(Duration.ofMillis(250))
        .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
        .build()) {

    var result = pipeline.execute("camera");
    // result.candidates() contains ranked IDs and source contributions.
    // result.retrievalResults() contains each provider's status and duration.
}
```

`ReciprocalRankFusion` combines source ranks without comparing their raw scores.
It is also the pipeline default with a rank constant of 60 when `.fusion(...)`
is omitted. An explicit fusion configuration sets both the strategy and its
output limit.
`WeightedMinMaxFusion` instead normalizes each source's returned scores and adds
them using optional positive weights on `RetrieverSpec`. `FirstSeenFusion` remains
available when provider registration order should determine the result. All three
strategies retain each provider's original rank and score for inspection.
Retriever implementations are supplied by the application. The example uses
in-memory lambdas; it does not contact an external provider.

## Modules

| Module | Responsibility |
| --- | --- |
| `kervan-api` | Public contracts and domain-independent value types |
| `kervan-core` | Concurrent pipeline, time budgets, failure handling, built-in fusion strategies, and identity ranker |
| `kervan-elasticsearch` | Official-client adapter that maps one Elasticsearch search response into a provider list |

Production dependencies flow from `kervan-core` to `kervan-api` to the JDK.
`kervan-elasticsearch` depends on `kervan-api` and the pinned official Java
client. API and core have no external production dependencies. The Maven group
and Java package prefix are `io.github.ahmetyasarkaplan.kervan`.

## Build locally

Install a JDK 21 and make it available through `JAVA_HOME` or `PATH`. The build
uses a Java 21 toolchain and does not download JDKs automatically. Git and network
access to the Gradle distribution, Plugin Portal, and Maven Central are needed
for an initial checkout and build. Docker is required for the Elasticsearch
integration tests included in `check`. A separate Gradle installation is
unnecessary.

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

See the [Elasticsearch adapter guide](docs/elasticsearch.md) for a native hybrid
request combined with an independent provider.

## Design documentation

- [Architecture and module boundaries](docs/architecture.md)
- [Java 21 and Gradle](docs/adr/0001-java-21-and-gradle.md)
- [Framework-independent module boundaries](docs/adr/0002-module-boundaries.md)
- [Provider boundaries and native search capabilities](docs/adr/0003-provider-boundaries.md)
- [Deadlines and failure policies](docs/adr/0004-deadlines-and-failure-policies.md)
- [Cross-provider fusion](docs/adr/0005-cross-provider-fusion.md)
- [Elasticsearch adapter boundary](docs/adr/0006-elasticsearch-adapter.md)
