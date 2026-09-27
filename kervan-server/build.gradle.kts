plugins { `java-library` }

dependencies {
  api(project(":kervan-spring-boot-starter"))
  implementation(platform(libs.spring.boot.bom))
  api(libs.spring.boot.starter.webmvc)

  testImplementation(platform(libs.spring.boot.bom))
  testImplementation(libs.spring.boot.starter.test)
}

tasks.withType<JavaCompile>().configureEach {
  options.compilerArgs.add("-parameters")
}
