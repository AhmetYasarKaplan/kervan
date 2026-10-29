plugins { `java-library` }

dependencies {
  api(project(":kervan-api"))
  api(libs.qdrant.java)
  // gRPC's older Guava must also be upgraded for independent Maven consumers.
  implementation(libs.guava)
  testImplementation(project(":kervan-core"))
  testImplementation(libs.testcontainers.qdrant)
}
