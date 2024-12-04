## fill-gradle

The fill gradle plugin is designed to publish projects to its downloads api, fill

## Usage

Declare the plugin 
```kts
plugins {
  id("io.papermc.fill.gradle") version "1.0.0"
}
```

# Configuring the plugin
You will also need to configure the plugin as well

```kts
fill {
  apiUrl("https://fill-data.papermc.io/") // This sets the api url to interact with
  apiToken("super-secure-token") // This sets the token

  project("paper") // This will set what project to publish it as
  versionFamily("1.21") // The version family to publish under, eg 1.20, 1.21, 1.22
  version("1.21.1") // This sets the specific minecraft version

  build { // This configures the information for the build 
    id = 1 // Id to release as, supports environment variable `BUILD_NUMBER`
    channel = BuildChannel.DEFAULT //

    downloads { // Configure downloads to release
      register("server") { // Name of the download
        file = tasks.jar.get().archiveFile // File to use
        nameResolver.set { project, _, version, build -> "$project-$version-$build.jar" } // File name to release it under 
      }
    }
  }
}
```
