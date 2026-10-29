plugins { `java-library` }

dependencies {
  api(project(":kervan-api"))
  api(libs.elasticsearch.java)
  // Publish patched transitive dependencies directly so Maven consumers receive them too.
  implementation(libs.jackson2.databind)
  implementation(libs.jackson2.core)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.core)
  implementation(libs.opentelemetry.api)
  testImplementation(project(":kervan-core"))
  testImplementation(libs.testcontainers.elasticsearch)
}
