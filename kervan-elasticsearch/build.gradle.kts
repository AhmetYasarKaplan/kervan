plugins { `java-library` }

dependencies {
  api(project(":kervan-api"))
  api(libs.elasticsearch.java)
  testImplementation(project(":kervan-core"))
  testImplementation(libs.testcontainers.elasticsearch)
}
