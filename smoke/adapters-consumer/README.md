# Published optional adapter consumer

This standalone Java 21 application resolves all eleven published Kervan library
artifacts, including provider adapters, reranking, HTTP, telemetry and testkit. It has
no project dependencies, composite build, source imports from the checkout,
direct SDK dependencies or JDBC driver. JUnit is resolved through the published testkit.

`check` runs a loopback HTTP server and exercises the published Cohere scorer
through `BatchScoringRanker`, including native response index mapping, document
ordering, one bulk hydration, model scores, and score/source provenance. It
constructs and executes the OpenSearch adapter with an in-memory native SDK
transport, constructs and closes pgvector without connecting to a database,
verifies PostgreSQL's driver is absent, and checks MMR selection. No external
service, Docker daemon, API key, or network request beyond loopback is needed.

Publish the checkout into an isolated Maven repository, then run from the
repository root in PowerShell:

```powershell
$smokeRepo = Join-Path (Get-Location) 'build/adapters-consumer-repo'
New-Item -ItemType Directory -Force -Path $smokeRepo | Out-Null
.\gradlew.bat "-Dmaven.repo.local=$smokeRepo" publishToMavenLocal
$kervanVersion = (Get-Content gradle.properties | Where-Object { $_ -match '^version=' } |
  Select-Object -First 1).Substring(8)
.\gradlew.bat -p smoke/adapters-consumer "-PkervanVersion=$kervanVersion" "-PkervanSmokeRepository=$smokeRepo" check
```

On Unix-like systems:

```sh
smoke_repo="$(mktemp -d)"
./gradlew "-Dmaven.repo.local=$smoke_repo" publishToMavenLocal
version="$(sed -n 's/^version=//p' gradle.properties)"
./gradlew -p smoke/adapters-consumer "-PkervanVersion=$version" \
  "-PkervanSmokeRepository=$smoke_repo" check
```

Kervan's group resolves exclusively from the required isolated repository, so
remote or stale local Kervan releases cannot satisfy this check. Other runtime
libraries resolve transitively from the published POMs and Gradle metadata.
The check verifies all eleven library versions and writes the resolved coordinates
to `build/reports/runtime-dependencies.tsv` for dependency audits.
The separate `smoke/consumer` continues proving the starter-only dependency path.
