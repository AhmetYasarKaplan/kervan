# ADR 0001: Java 21 and Gradle

Status: Accepted

## Context

Kervan needs a consistent Java baseline and a repeatable build for multiple
libraries. Contributors should be able to verify a checkout without installing
a separate build tool.

## Decision

Use Java 21 with a Gradle Wrapper and Kotlin DSL. Pin the Gradle distribution and
its SHA-256 checksum. Compile with a Java 21 toolchain, `--release 21`, UTF-8, and
compiler warnings. Tests use the Java 21 toolchain; preview features are disabled.

Use `./gradlew clean check` for local and CI verification. Share the small amount
of common build configuration at the root and keep tool/dependency versions in
a version catalog. Use JUnit 5, AssertJ, Spotless, and JaCoCo initially.

## Consequences

Contributors need a JDK 21 and network access for the first build. CI supplies
Temurin Java 21. Build and test dependencies stay outside the production API.
Java 21 makes virtual threads available for later execution work, but this
decision does not establish concurrency or executor lifecycle semantics.
