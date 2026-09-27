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
  "kervan-spring-boot-starter",
  "kervan-observability",
  "kervan-testkit",
  "examples:product-discovery",
)
