plugins { `java-library` }

dependencies {
  api(project(":kervan-core"))
  api(libs.micrometer.core)
  api(libs.opentelemetry.api)
  api(libs.slf4j.api)

  testImplementation(libs.opentelemetry.sdk.testing)
  testImplementation(libs.logback.classic)
}
