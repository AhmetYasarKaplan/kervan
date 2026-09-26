plugins { `java-library` }

dependencies {
  api(project(":kervan-api"))
  api(platform(libs.junit.bom))
  api(libs.junit.jupiter.api)
}
