plugins { `java-library` }

dependencies {
  api(project(":kervan-core"))
  implementation(platform(libs.spring.boot.bom))
  api(libs.spring.boot.starter)
  api(libs.spring.boot.autoconfigure)
  compileOnly(libs.spring.boot.health)
  compileOnly(libs.jackson.annotations)
  annotationProcessor(libs.spring.boot.configuration.processor)
  annotationProcessor(libs.spring.boot.autoconfigure.processor)

  testImplementation(platform(libs.spring.boot.bom))
  testImplementation(enforcedPlatform(libs.junit.bom))
  testImplementation(libs.spring.boot.test)
  testImplementation(libs.spring.boot.starter.actuator)
  testCompileOnly(libs.jackson.annotations)
}

tasks.withType<JavaCompile>().configureEach {
  options.compilerArgs.addAll(listOf("-parameters", "-Xlint:-processing"))
}
