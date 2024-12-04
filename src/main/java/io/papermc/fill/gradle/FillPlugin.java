/*
 * Copyright 2024 PaperMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.papermc.fill.gradle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.papermc.fill.gradle.task.PublishToFillTask;
import net.kyori.indra.git.IndraGitExtension;
import net.kyori.mammoth.Extensions;
import net.kyori.mammoth.ProjectPlugin;
import org.eclipse.jgit.api.Git;
import org.gradle.api.Project;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.api.plugins.PluginContainer;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskContainer;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class FillPlugin implements ProjectPlugin {
  public static final ObjectMapper MAPPER = new ObjectMapper()
    .registerModule(new JavaTimeModule());

  @Override
  public void apply(
    final Project project,
    final PluginContainer plugins,
    final ExtensionContainer extensions,
    final TaskContainer tasks
  ) {
    plugins.apply("net.kyori.indra.git");

    final FillExtension extension = Extensions.findOrCreate(extensions, FillExtension.NAME, FillExtension.class, FillExtensionImpl.class);

    final Provider<Git> git = project.provider(() -> project.getExtensions().getByType(IndraGitExtension.class).git());

    tasks.register(PublishToFillTask.NAME, PublishToFillTask.class, task -> {
      task.getExtension().set(extension);
      task.getGit().set(git);
    });
  }
}
