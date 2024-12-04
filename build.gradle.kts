plugins {
  alias(libs.plugins.indra)
  alias(libs.plugins.indra.spotless)
  id("java-gradle-plugin")
}

gradlePlugin {
  plugins.register("fill") {
    id = "io.papermc.fill.gradle"
    displayName = "Fill"
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
}
