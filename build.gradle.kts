import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
  base
  alias(libs.plugins.spotless)
}

allprojects {
  group = "io.github.ahmetyasarkaplan.kervan"
  version = rootProject.version
}

val junitBom = libs.junit.bom
val junitJupiter = libs.junit.jupiter
val junitLauncher = libs.junit.platform.launcher
val assertjCore = libs.assertj.core
val jacocoVersion = libs.versions.jacoco.get()

subprojects {
  plugins.withId("java-library") {
    pluginManager.apply("jacoco")

    extensions.configure<JavaPluginExtension> {
      toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    tasks.withType<JavaCompile>().configureEach {
      options.release.set(21)
      options.encoding = "UTF-8"
      options.compilerArgs.add("-Xlint:all")
    }

    dependencies {
      add("testImplementation", platform(junitBom))
      add("testImplementation", junitJupiter)
      add("testImplementation", assertjCore)
      add("testRuntimeOnly", junitLauncher)
    }

    tasks.withType<Test>().configureEach {
      useJUnitPlatform()
      testLogging { events("failed", "skipped") }
    }

    extensions.configure<JacocoPluginExtension> { toolVersion = jacocoVersion }

    tasks.named<JacocoReport>("jacocoTestReport") {
      dependsOn(tasks.named("test"))
      reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
      }
    }

    val modulePath = path
    val productionClasspaths =
      listOf("compileClasspath", "runtimeClasspath").map { configurations.getByName(it) }

    val verifyModuleBoundaries =
      tasks.register("verifyModuleBoundaries") {
        group = "verification"
        description = "Verifies that production classpaths respect the module boundaries."

        doLast {
          productionClasspaths.forEach { configuration ->
            val incoming = configuration.incoming
            val rootId = incoming.resolutionResult.rootComponent.get().id
            fun isAllowed(id: ComponentIdentifier): Boolean =
              modulePath == ":kervan-core" &&
                id is ProjectComponentIdentifier &&
                id.buildTreePath == ":kervan-api"

            val forbidden =
              (incoming.resolutionResult.allComponents.map { it.id }.filter { it != rootId } +
                  incoming.artifacts.artifacts.map { it.id.componentIdentifier })
                .filterNot(::isAllowed)
                .distinct()

            check(forbidden.isEmpty()) {
              "$modulePath:${configuration.name} has forbidden production dependencies: " +
                forbidden.joinToString { it.displayName }
            }
          }
        }
      }

    tasks.named("check") {
      dependsOn(verifyModuleBoundaries, tasks.named("jacocoTestReport"))
    }
  }
}

spotless {
  java {
    target("kervan-*/src/**/*.java")
    googleJavaFormat(libs.versions.google.java.format.get())
  }
  kotlinGradle {
    target("*.gradle.kts", "kervan-*/build.gradle.kts")
    ktfmt(libs.versions.ktfmt.get()).googleStyle()
  }
  format("misc") {
    target(
      ".editorconfig",
      ".gitattributes",
      ".gitignore",
      "README.md",
      "docs/**/*.md",
      ".github/workflows/*.yml",
      "gradle.properties",
      "gradle/libs.versions.toml",
    )
    trimTrailingWhitespace()
    endWithNewline()
  }
}

tasks.named("check") {
  dependsOn("spotlessCheck", subprojects.map { "${it.path}:check" })
}

tasks.named("clean") { dependsOn(subprojects.map { "${it.path}:clean" }) }

tasks.wrapper {
  gradleVersion = "9.7.1"
  distributionType = Wrapper.DistributionType.BIN
  distributionSha256Sum = "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a"
}
