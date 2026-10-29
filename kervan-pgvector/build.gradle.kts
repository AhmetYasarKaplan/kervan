plugins { `java-library` }

dependencies {
  api(project(":kervan-api"))
  testImplementation(project(":kervan-core"))
  testImplementation(libs.testcontainers.core)
  testImplementation(libs.postgresql)
}
