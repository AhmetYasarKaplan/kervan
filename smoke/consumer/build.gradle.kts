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
  implementation("io.github.ahmetyasarkaplan.kervan:kervan-spring-boot-starter:$kervanVersion")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

application { mainClass.set("smoke.consumer.ConsumerSmokeApplication") }

tasks.named("check") { dependsOn(tasks.named("run")) }
