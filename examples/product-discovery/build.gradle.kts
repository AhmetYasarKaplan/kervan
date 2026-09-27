plugins { application }

dependencies {
  implementation(project(":kervan-spring-boot-starter"))
  implementation(project(":kervan-elasticsearch"))
  implementation(platform(libs.spring.boot.bom))
  implementation(libs.spring.boot.starter)

  testImplementation(project(":kervan-testkit"))
  testImplementation(platform(libs.spring.boot.bom))
  testImplementation(enforcedPlatform(libs.junit.bom))
  testImplementation(libs.spring.boot.starter.test)
}

application {
  mainClass.set("io.github.ahmetyasarkaplan.kervan.example.ProductDiscoveryApplication")
}

tasks.register<JavaExec>("runRecommendations") {
  group = "application"
  description = "Starts the separate demo HTTP recommendation provider on port 8085."
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("io.github.ahmetyasarkaplan.kervan.example.RecommendationStubServer")
}
