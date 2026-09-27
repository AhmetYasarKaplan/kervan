plugins { `java-library` }

dependencies {
  api(project(":kervan-api"))
  api(libs.qdrant.java)
  testImplementation(project(":kervan-core"))
  testImplementation(libs.testcontainers.qdrant)
}
