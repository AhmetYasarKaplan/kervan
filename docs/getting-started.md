# Getting started

Kervan runs inside a Java 21 application. Start with two independent candidate
providers and a single pipeline. The steps below build modules from source.

## Build from this repository

Install JDK 21 and set `JAVA_HOME`. The Gradle wrapper downloads its pinned
distribution and dependencies on the first run. Run the fast API and core checks:

```sh
./gradlew :kervan-api:check :kervan-core:check
```

Windows PowerShell: use `.\gradlew.bat` in place of `./gradlew`. For the full
suite, run `./gradlew clean check`; the Elasticsearch, OpenSearch, Qdrant and
pgvector integration tests require Docker.

## Use the source release in another application

Build and install the library artifacts into your local Maven repository:

```sh
./gradlew publishToMavenLocal
```

In a separate Gradle application, resolve the core artifact:

```kotlin
repositories {
  mavenLocal()
  mavenCentral()
}

dependencies {
  implementation("io.github.ahmetyasarkaplan.kervan:kervan-core:1.0.0")
}
```

The optional modules use the same group and version. Add the provider adapters
your application needs; core exposes the shared API transitively. For a Maven
application, the coordinates are the same and the default local repository
contains the artifacts installed by `publishToMavenLocal`.

See the independent [starter](../smoke/consumer/README.md) and
[adapter](../smoke/adapters-consumer/README.md) applications for checks that resolve
generated Maven POMs without using this repository's source classpath.

## Use the GitHub Maven bundle

The [1.0.0 GitHub release](https://github.com/AhmetYasarKaplan/kervan/releases/tag/v1.0.0)
also provides `kervan-1.0.0-maven.zip` and `SHA256SUMS`. Verify the archive's
SHA-256 against `SHA256SUMS`, then extract it to a local directory. The extracted
directory contains the Maven `io/github/...` layout with POMs, main, sources and
Javadoc JARs for all eleven modules.

Use that directory as the repository for Kervan's group and Maven Central for
external dependencies:

```kotlin
repositories {
  exclusiveContent {
    forRepository { maven { url = uri("/absolute/path/to/extracted-kervan") } }
    filter { includeGroup("io.github.ahmetyasarkaplan.kervan") }
  }
  mavenCentral()
}

dependencies {
  implementation("io.github.ahmetyasarkaplan.kervan:kervan-core:1.0.0")
}
```

On Windows use a path such as `C:/dev/kervan-artifacts`. This GitHub bundle is a
local Maven repository distribution; it does not require a Maven Central upload.

## Develop inside this repository

In a Gradle project included in this build, depend on `kervan-core`:

```kotlin
dependencies {
  implementation(project(":kervan-core"))
}
```

The [README's embedded example](../README.md#embedded-usage) constructs a pipeline
with two retriever lambdas. It returns canonical candidate IDs, provider status,
original source ranks and scores, and a partial-result flag. Close a manually
constructed pipeline when the application no longer needs it.

## Use Spring Boot configuration

The Spring Boot starter takes retriever beans from your application and assembles
the pipeline at startup. Add `implementation(project(":kervan-spring-boot-starter"))`
in this repository. Declare each bean with concrete `CandidateRetriever<Q, ID>`
types, then refer to its bean name from YAML:

```yaml
kervan:
  pipelines:
    discovery:
      timeout: 500ms
      result-size: 10
      failure-policy: partial-results
      retrievers:
        - name: search
          bean: searchRetriever
          candidate-limit: 20
          timeout: 300ms
          required: true
        - name: recommendations
          bean: recommendationRetriever
          candidate-limit: 10
          timeout: 150ms
          required: false
      fusion:
        type: rrf
        rank-constant: 60
        candidate-limit: 30
```

Inject `KervanPipelineRegistry` and call
`registry.pipeline("discovery", Query.class, String.class).execute(query)`.
The Spring context closes its pipelines. See the [Spring Boot guide](spring-boot.md)
for validation and custom strategies.

## Run the product discovery example

For a service-free demonstration of fusion followed by batch reranking, run
`./gradlew :examples:product-discovery:runReranking`. It prints the changed
ordering and separate fusion/reranking scores. The scoring rule is a transparent
title match; applications can supply a model through the same callback.

The example combines three genuine provider boundaries:

1. Elasticsearch 9.5.0 performs lexical `match` and vector `knn` in **one native
   search request**. Kervan receives its ordered hits as one provider result.
2. A separate Java process serves recommendations over HTTP. The example adapter
   maps each native product ID to the canonical `catalog:` ID before returning it.
3. A local popularity provider returns the same canonical IDs.

Start Docker, then use three terminals at the repository root:

```sh
# Terminal 1: Elasticsearch (waits until the HTTP endpoint is healthy)
docker compose -f examples/product-discovery/compose.yaml up --wait -d
docker compose -f examples/product-discovery/compose.yaml ps

# Terminal 2: independent HTTP recommendation provider
./gradlew :examples:product-discovery:runRecommendations

# Terminal 3: seed the catalog and execute one pipeline request
./gradlew :examples:product-discovery:run
```

The second command stays running. On Windows, use `.\gradlew.bat` for the last two
commands. The example uses local ports 9200 and 8085. You can override the
Elasticsearch and recommendation URLs with Spring properties
`demo.elasticsearch-url` and `demo.recommendations-url`. The fixed three-value
query vector is application-supplied demo data; Kervan does not generate vectors.

The output lists ranked IDs with their contributing sources, then the status of
each provider and the ranking outcome. One batch catalog lookup and a title-token
scoring rule rerank the fused candidates for `camera tripod`.
If the optional HTTP service is unavailable, the result should be
marked partial and retain search/popularity results. If required Elasticsearch is
unavailable, execution fails. Stop the demo service with Ctrl+C and the local
Elasticsearch container with:

```sh
docker compose -f examples/product-discovery/compose.yaml down
```

Use Elasticsearch or OpenSearch native features alone when all retrieval happens
inside one index or engine. Kervan adds value when the application must coordinate
independently operated providers under one deadline and failure policy. It does
not implement indexing, embedding generation, or engine-local hybrid ranking.
