/*
 * Copyright 2024 PaperMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.papermc.fill.gradle.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionalTest {
  @Test
  void publishTaskDryRun(final @TempDir Path projectDirectory) throws IOException {
    Files.writeString(projectDirectory.resolve("settings.gradle"), "rootProject.name = 'fill-test'\n");
    Files.writeString(projectDirectory.resolve("build.gradle"), """
      plugins {
        id 'io.papermc.fill.gradle'
      }

      fill {
        apiUrl = 'https://example.invalid/'
        apiToken = 'test-token'
        project = 'paper'
        versionFamily = '1.21'
        version = '1.21.1'
        build.id = 1
      }
      """);

    final GradleRunner runner = GradleRunner.create()
      .withProjectDir(projectDirectory.toFile())
      .withPluginClasspath()
      .withArguments("publishToFill", "--dry-run", "--configuration-cache", "--stacktrace");

    final BuildResult first = runner.build();
    final BuildResult reused = runner.build();

    assertTrue(first.getOutput().contains(":publishToFill SKIPPED"));
    assertTrue(reused.getOutput().contains(":publishToFill SKIPPED"));
    assertTrue(reused.getOutput().contains("Configuration cache entry reused."));
  }
}
