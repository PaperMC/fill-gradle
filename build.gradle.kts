plugins {
  alias(libs.plugins.indra)
  alias(libs.plugins.indra.spotless)
  id("java-gradle-plugin")
  alias(libs.plugins.publish.plugin)
}

gradlePlugin {
  website = "https://github.com/PaperMC/fill-gradle"
  vcsUrl = "https://github.com/PaperMC/fill-gradle"

  plugins.register("fill") {
    id = "io.papermc.fill.gradle"
    displayName = "Fill"
    description = "Gradle plugin for publishing to Fill"
    tags = listOf("fill", "publishing")
    implementationClass = "io.papermc.fill.gradle.FillPlugin"
  }
}

indra {
  apache2License()

  github("papermc", "fill-gradle")

  javaVersions {
    target(21)
  }
}

publishing {
  repositories {
    maven("https://repo.papermc.io/repository/maven-snapshots/") {
      name = "papermc"
      credentials(PasswordCredentials::class)
      mavenContent {
        snapshotsOnly()
      }
    }
  }
}

indraSpotlessLicenser {
  licenseHeaderFile(rootProject.file("license_header.txt"))
}

repositories {
  mavenCentral()
  gradlePluginPortal()
}

dependencies {
  compileOnlyApi(libs.jspecify)

  implementation(libs.guava)
  implementation(libs.indra.git)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.datatype.jsr310)
  implementation(libs.mammoth)

  testImplementation(libs.jUnit)
  testRuntimeOnly("org.junit.platform", "junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
}
