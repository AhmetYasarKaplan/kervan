# Published starter consumer smoke

This is a standalone Gradle application, outside Kervan's multi-project build. It
declares only the published `kervan-spring-boot-starter` as a Kervan dependency.
Its run task verifies Spring Boot auto-configuration, application-provided
retrievers and ranker, source provenance, and optional-provider failure behavior.
Two pipelines retain the legacy ranker configuration. A third uses the published
batch scorer and bounded ranker settings, verifying model ordering, separate
fusion/model scores, the input and output limits, and fused fallback on model
failure. Its ranking degradation remains independent of provider completeness.

Publish the current checkout to a disposable Maven repository, then run the
consumer against that repository. On Unix-like systems, from the repository root:

```sh
smoke_repo="$(mktemp -d)"
./gradlew "-Dmaven.repo.local=$smoke_repo" publishToMavenLocal
version="$(sed -n 's/^version=//p' gradle.properties)"
./gradlew -p smoke/consumer "-PkervanVersion=$version" \
  "-PkervanSmokeRepository=$smoke_repo" check
```

The consumer resolves Kervan's group exclusively from the disposable repository,
so a stale or remote release cannot satisfy the check. The repository path is
required and is not the developer's normal Maven repository.

On Windows PowerShell, use the same isolated directory:

```powershell
$smokeRepo = Join-Path (Get-Location) 'build/consumer-regression-repo'
New-Item -ItemType Directory -Force -Path $smokeRepo | Out-Null
.\gradlew.bat "-Dmaven.repo.local=$smokeRepo" publishToMavenLocal
$kervanVersion = (Get-Content gradle.properties | Where-Object { $_ -match '^version=' } |
  Select-Object -First 1).Substring(8)
.\gradlew.bat -p smoke/consumer "-PkervanVersion=$kervanVersion" "-PkervanSmokeRepository=$smokeRepo" check
```
