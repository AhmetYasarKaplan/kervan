plugins {
  java
  application
}

val kervanVersion =
  providers.gradleProperty("kervanVersion").orNull
    ?: error("Pass -PkervanVersion=<published version>")
val smokeRepository =
  providers.gradleProperty("kervanSmokeRepository").orNull
    ?: error("Pass -PkervanSmokeRepository=<isolated Maven repository path>")

repositories {
  exclusiveContent {
    forRepository { maven { url = uri(smokeRepository) } }
    filter { includeGroup("io.github.ahmetyasarkaplan.kervan") }
  }
  mavenCentral()
}

dependencies {
  listOf(
      "api",
      "core",
      "elasticsearch",
      "qdrant",
      "opensearch",
      "pgvector",
      "reranking",
      "spring-boot-starter",
      "server",
      "observability",
      "testkit",
    )
    .forEach { implementation("io.github.ahmetyasarkaplan.kervan:kervan-$it:$kervanVersion") }
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

application { mainClass.set("smoke.adapters.AdaptersConsumerApplication") }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror")) }

val verifyPublishedRuntime = tasks.register("verifyPublishedRuntime") {
  description = "Checks all eleven published modules and records their resolved runtime dependencies."
  val runtimeClasspath = configurations.named("runtimeClasspath")
  val report = layout.buildDirectory.file("reports/runtime-dependencies.tsv")
  outputs.file(report)
  // Dependency mediation can change even when the local consumer sources have not changed.
  outputs.upToDateWhen { false }
  doLast {
    val modules =
      runtimeClasspath.get().incoming.resolutionResult.allComponents
        .filter { it.id is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
        .mapNotNull { it.moduleVersion }
        .distinctBy { "${it.group}:${it.name}:${it.version}" }
    val kervanModules = modules.filter { it.group == "io.github.ahmetyasarkaplan.kervan" }
    check(kervanModules.size == 11 && kervanModules.all { it.version == kervanVersion }) {
      "Expected all eleven Kervan modules at version $kervanVersion"
    }
    report.get().asFile.apply {
      parentFile.mkdirs()
      writeText(modules.sortedBy { "${it.group}:${it.name}" }
        .joinToString("\n", postfix = "\n") { "${it.group}:${it.name}\t${it.version}" })
    }
    println("All eleven published modules resolved; runtime dependency report written")
  }
}

tasks.named("check") { dependsOn(tasks.named("run"), verifyPublishedRuntime) }
