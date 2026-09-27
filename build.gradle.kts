import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.w3c.dom.Element

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
val libraryModules =
  listOf(
    "kervan-api",
    "kervan-core",
    "kervan-elasticsearch",
    "kervan-spring-boot-starter",
    "kervan-observability",
    "kervan-testkit",
  )
val publicationVersion = version.toString()
val publicationGroup = group.toString()
val publicationBuildDirectories = libraryModules.associateWith { module ->
  project(":$module").layout.buildDirectory.get().asFile
}
val centralGroupPath = "io/github/ahmetyasarkaplan/kervan"
val centralStagingDirectory = layout.buildDirectory.dir("central-staging")

val cleanCentralStaging =
  tasks.register<Delete>("cleanCentralStaging") {
    group = "publishing"
    description = "Removes the previous local Central staging files."
    doFirst {
      check(!publicationVersion.endsWith("-SNAPSHOT")) {
        "Central bundles require a release version"
      }
    }
    delete(centralStagingDirectory)
  }

subprojects {
  plugins.withId("java-library") {
    pluginManager.apply("maven-publish")
    pluginManager.apply("signing")

    extensions.configure<JavaPluginExtension> {
      withSourcesJar()
      withJavadocJar()
    }

    tasks.withType<Jar>().configureEach {
      from(rootProject.file("LICENSE")) { into("META-INF") }
    }
    tasks.withType<GenerateModuleMetadata>().configureEach { enabled = false }

    extensions.configure<PublishingExtension> {
      publications.create<MavenPublication>("mavenJava") {
        from(components["java"])
        pom {
          name.set("Kervan ${project.name}")
          description.set("${project.name} module of the Kervan retrieval orchestration library")
          url.set("https://github.com/AhmetYasarKaplan/kervan")
          licenses {
            license {
              name.set("The Apache Software License, Version 2.0")
              url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
              distribution.set("repo")
            }
          }
          developers {
            developer {
              id.set("AhmetYasarKaplan")
              name.set("Ahmet Yasar Kaplan")
              url.set("https://github.com/AhmetYasarKaplan")
            }
          }
          scm {
            connection.set("scm:git:https://github.com/AhmetYasarKaplan/kervan.git")
            developerConnection.set("scm:git:ssh://git@github.com/AhmetYasarKaplan/kervan.git")
            url.set("https://github.com/AhmetYasarKaplan/kervan")
          }
        }
      }
      repositories {
        maven {
          name = "centralStaging"
          url = uri(rootProject.layout.buildDirectory.dir("central-staging").get().asFile)
        }
      }
    }

    val signingKey = providers.environmentVariable("KERVAN_SIGNING_KEY").orNull
    val publicationVersion = project.version.toString()
    if (signingKey != null) {
      extensions.configure<SigningExtension> {
        useInMemoryPgpKeys(
          signingKey,
          providers.environmentVariable("KERVAN_SIGNING_PASSWORD").orNull,
        )
        sign(extensions.getByType<PublishingExtension>().publications["mavenJava"])
      }
    }
    tasks.withType<PublishToMavenRepository>().configureEach {
      if (name.endsWith("ToCentralStagingRepository")) {
        dependsOn(rootProject.tasks.named("cleanCentralStaging"))
      }
      doFirst {
        if (!publicationVersion.endsWith("-SNAPSHOT")) {
          check(
            signingKey != null && providers.environmentVariable("KERVAN_SIGNING_PASSWORD").isPresent
          ) {
            "A Maven Central release requires KERVAN_SIGNING_KEY and KERVAN_SIGNING_PASSWORD"
          }
        }
      }
    }
  }

  plugins.withId("java") {
    pluginManager.apply("jacoco")

    extensions.configure<JavaPluginExtension> {
      toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    tasks.named<Javadoc>("javadoc") {
      (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
    }
    tasks.named("check") { dependsOn(tasks.named("javadoc")) }

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
              when (modulePath) {
                ":kervan-core" ->
                  id is ProjectComponentIdentifier && id.buildTreePath == ":kervan-api"
                ":kervan-elasticsearch" ->
                  id !is ProjectComponentIdentifier || id.buildTreePath == ":kervan-api"
                ":kervan-spring-boot-starter" ->
                  when (id) {
                    is ProjectComponentIdentifier ->
                      id.buildTreePath == ":kervan-api" || id.buildTreePath == ":kervan-core"
                    is ModuleComponentIdentifier ->
                      id.group != "co.elastic.clients" && !id.group.startsWith("org.elasticsearch")
                    else -> false
                  }
                ":kervan-observability" ->
                  when (id) {
                    is ProjectComponentIdentifier ->
                      id.buildTreePath == ":kervan-api" || id.buildTreePath == ":kervan-core"
                    is ModuleComponentIdentifier ->
                      id.group in
                        setOf(
                          "io.micrometer",
                          "io.opentelemetry",
                          "org.slf4j",
                          "org.jspecify",
                          "org.hdrhistogram",
                          "org.latencyutils",
                        )
                    else -> false
                  }
                ":kervan-testkit" ->
                  when (id) {
                    is ProjectComponentIdentifier -> id.buildTreePath == ":kervan-api"
                    is ModuleComponentIdentifier ->
                      id.group in
                        setOf(
                          "org.junit",
                          "org.junit.jupiter",
                          "org.junit.platform",
                          "org.apiguardian",
                          "org.opentest4j",
                        )
                    else -> false
                  }
                else -> modulePath == ":examples:product-discovery"
              }

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

val verifyPublicationArtifacts =
  tasks.register("verifyPublicationArtifacts") {
    group = "verification"
    description = "Checks the six generated Maven publications without signing credentials."
    dependsOn(
      libraryModules.flatMap {
        listOf(
          ":$it:jar",
          ":$it:sourcesJar",
          ":$it:javadocJar",
          ":$it:generatePomFileForMavenJavaPublication",
        )
      }
    )

    doLast {
      val xmlFactory = DocumentBuilderFactory.newInstance()
      xmlFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      xmlFactory.setFeature("http://xml.org/sax/features/external-general-entities", false)
      xmlFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)

      fun Element.directChildren(name: String): List<Element> =
        (0 until childNodes.length)
          .map { childNodes.item(it) }
          .filterIsInstance<Element>()
          .filter { it.tagName == name }

      fun Element.directChild(name: String): Element? = directChildren(name).firstOrNull()

      fun Element.requiredText(name: String): String =
        directChild(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
          ?: error("Missing $name in Maven POM")

      libraryModules.forEach { module ->
        val moduleBuildDirectory = publicationBuildDirectories.getValue(module)
        val pom = moduleBuildDirectory.resolve("publications/mavenJava/pom-default.xml")
        check(pom.isFile) { "Missing POM: $pom" }
        val root = xmlFactory.newDocumentBuilder().parse(pom).documentElement
        check(root.requiredText("groupId") == publicationGroup) {
          "Incorrect groupId in $pom"
        }
        check(root.requiredText("artifactId") == module) { "Incorrect artifactId in $pom" }
        check(root.requiredText("version") == publicationVersion) { "Incorrect version in $pom" }
        listOf("name", "description", "url").forEach { root.requiredText(it) }
        val license =
          root.directChild("licenses")?.directChild("license") ?: error("Missing license in $pom")
        license.requiredText("name")
        license.requiredText("url")
        root.directChild("developers")?.directChild("developer")?.requiredText("name")
          ?: error("Missing developer in $pom")
        val scm = root.directChild("scm") ?: error("Missing SCM in $pom")
        scm.requiredText("connection")
        scm.requiredText("url")
        val importedBootBom =
          root
            .directChild("dependencyManagement")
            ?.directChild("dependencies")
            ?.directChildren("dependency")
            ?.any { dependency ->
              dependency.requiredText("groupId") == "org.springframework.boot" &&
                dependency.requiredText("artifactId") == "spring-boot-dependencies" &&
                dependency.requiredText("version").isNotEmpty() &&
                dependency.requiredText("scope") == "import"
            } ?: false
        val dependencies = root.directChild("dependencies")?.directChildren("dependency").orEmpty()
        dependencies.forEach { dependency ->
          val dependencyGroup = dependency.requiredText("groupId")
          dependency.requiredText("artifactId")
          val declaredVersion = dependency.directChild("version")?.textContent?.trim()
          check(
            !declaredVersion.isNullOrEmpty() ||
              (dependencyGroup == "org.springframework.boot" && importedBootBom)
          ) {
            "Unmanaged dependency version in $pom"
          }
        }
        if (module == "kervan-spring-boot-starter") {
          listOf("spring-boot-starter", "spring-boot-autoconfigure").forEach { artifact ->
            val dependency =
              dependencies.singleOrNull {
                it.requiredText("groupId") == "org.springframework.boot" &&
                  it.requiredText("artifactId") == artifact
              } ?: error("Missing $artifact dependency in $pom")
            check(dependency.requiredText("scope") == "compile") {
              "$artifact must have compile scope in $pom"
            }
          }
        }

        listOf(
            "$module-$publicationVersion.jar",
            "$module-$publicationVersion-sources.jar",
            "$module-$publicationVersion-javadoc.jar",
          )
          .forEach { filename ->
            val jar = moduleBuildDirectory.resolve("libs/$filename")
            check(jar.isFile && jar.length() > 0) { "Missing or empty JAR: $jar" }
            ZipFile(jar).use { zip ->
              check(zip.getEntry("META-INF/LICENSE") != null) { "Missing license in $jar" }
            }
          }
      }
    }
  }

val verifyCentralStaging =
  tasks.register("verifyCentralStaging") {
    group = "verification"
    description = "Checks signatures, checksums, metadata, and layout before Portal upload."
    dependsOn(
      verifyPublicationArtifacts,
      libraryModules.map { ":$it:publishMavenJavaPublicationToCentralStagingRepository" },
    )

    doLast {
      val staging = centralStagingDirectory.get().asFile
      val version = publicationVersion
      val expectedFiles = mutableSetOf<java.io.File>()
      libraryModules.forEach { module ->
        val versionDirectory = staging.resolve("$centralGroupPath/$module/$version")
        check(versionDirectory.isDirectory) { "Missing staged module: $versionDirectory" }
        listOf(
            "$module-$version.jar",
            "$module-$version-sources.jar",
            "$module-$version-javadoc.jar",
            "$module-$version.pom",
          )
          .forEach { name ->
            val artifact = versionDirectory.resolve(name)
            check(artifact.isFile && artifact.length() > 0) {
              "Missing or empty artifact: $artifact"
            }
            expectedFiles.add(artifact)
            val signature = versionDirectory.resolve("$name.asc")
            check(signature.isFile && signature.readText().contains("BEGIN PGP SIGNATURE")) {
              "Missing ASCII-armored signature: $signature"
            }
            expectedFiles.add(signature)
            mapOf(
                "md5" to "MD5",
                "sha1" to "SHA-1",
                "sha256" to "SHA-256",
                "sha512" to "SHA-512",
              )
              .forEach { (extension, algorithm) ->
                val checksum = versionDirectory.resolve("$name.$extension")
                if (extension == "md5" || extension == "sha1" || checksum.exists()) {
                  check(checksum.isFile) { "Missing checksum: $checksum" }
                  val actual =
                    HexFormat.of()
                      .formatHex(MessageDigest.getInstance(algorithm).digest(artifact.readBytes()))
                  check(checksum.readText().trim().equals(actual, ignoreCase = true)) {
                    "Invalid $extension checksum: $checksum"
                  }
                  expectedFiles.add(checksum)
                }
              }
          }
      }
      val stagedVersionFiles = libraryModules.flatMap { module ->
        staging.resolve("$centralGroupPath/$module/$version").listFiles()?.filter { it.isFile }
          ?: emptyList()
      }
      val unexpected = stagedVersionFiles.filterNot { it in expectedFiles }
      check(unexpected.isEmpty()) { "Unexpected staged files: ${unexpected.joinToString()}" }
    }
  }

tasks.register<Zip>("centralBundle") {
  group = "publishing"
  description = "Creates one validated Maven Central Portal upload ZIP."
  dependsOn(verifyCentralStaging)
  archiveFileName.set("kervan-$publicationVersion-central.zip")
  destinationDirectory.set(layout.buildDirectory.dir("distributions"))
  setIncludeEmptyDirs(false)
  from(centralStagingDirectory) {
    libraryModules.forEach { include("$centralGroupPath/$it/$publicationVersion/*") }
  }
  doLast {
    val archive = archiveFile.get().asFile
    check(archive.length() <= 1_000_000_000L) { "Central Portal ZIP exceeds 1 GB: $archive" }
    val staging = centralStagingDirectory.get().asFile
    val expectedEntries =
      libraryModules
        .flatMap { module ->
          staging.resolve("$centralGroupPath/$module/$publicationVersion").listFiles()?.toList()
            ?: emptyList()
        }
        .filter { it.isFile }
        .map { staging.toPath().relativize(it.toPath()).toString().replace('\\', '/') }
        .toSet()
    ZipFile(archive).use { zip ->
      val actualEntries =
        zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
      check(actualEntries == expectedEntries) {
        "Central Portal ZIP contents do not match validated staging files"
      }
    }
  }
}

spotless {
  java {
    target("kervan-*/src/**/*.java", "examples/*/src/**/*.java")
    googleJavaFormat(libs.versions.google.java.format.get())
  }
  kotlinGradle {
    target(
      "*.gradle.kts",
      "kervan-*/build.gradle.kts",
      "examples/build.gradle.kts",
      "examples/*/build.gradle.kts",
    )
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
