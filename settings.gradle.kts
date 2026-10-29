pluginManagement {
  repositories { gradlePluginPortal() }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories { mavenCentral() }
}

rootProject.name = "kervan"

include(
  "kervan-api",
  "kervan-core",
  "kervan-elasticsearch",
  "kervan-qdrant",
  "kervan-opensearch",
  "kervan-pgvector",
  "kervan-reranking",
  "kervan-spring-boot-starter",
  "kervan-server",
  "kervan-observability",
  "kervan-testkit",
  "examples:product-discovery",
)
