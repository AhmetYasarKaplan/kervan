plugins { `java-library` }

dependencies {
  api(project(":kervan-api"))
  api(libs.opensearch.java)
  // Keep the patched JSON runtime in the published POM, not only Gradle constraints.
  implementation(libs.jackson2.databind)
  implementation(libs.jackson2.core)
  testImplementation(project(":kervan-core"))
  testImplementation(libs.testcontainers.core)
}
