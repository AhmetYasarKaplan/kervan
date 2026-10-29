plugins { `java-library` }

dependencies {
  api(project(":kervan-api"))
  api(libs.jackson.databind)
}
