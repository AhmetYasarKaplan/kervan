plugins { `java-library` }

dependencies {
  api(project(":kervan-spring-boot-starter"))
  implementation(platform(libs.spring.boot.bom))
  api(libs.spring.boot.starter.webmvc)
  // Publish security updates newer than the Boot BOM as direct runtime dependencies.
  implementation(libs.jackson.databind)
  implementation(libs.jackson.core)
  runtimeOnly(libs.tomcat.embed.core)
  runtimeOnly(libs.tomcat.embed.el)
  runtimeOnly(libs.tomcat.embed.websocket)

  testImplementation(platform(libs.spring.boot.bom))
  testImplementation(libs.spring.boot.starter.test)
}

tasks.withType<JavaCompile>().configureEach {
  options.compilerArgs.add("-parameters")
}
