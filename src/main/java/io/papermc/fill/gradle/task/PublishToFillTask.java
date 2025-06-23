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
package io.papermc.fill.gradle.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.hash.Hashing;
import io.papermc.fill.gradle.FillExtension;
import io.papermc.fill.gradle.FillPlugin;
import io.papermc.fill.model.Checksums;
import io.papermc.fill.model.Commit;
import io.papermc.fill.model.Download;
import io.papermc.fill.model.request.PublishRequest;
import io.papermc.fill.model.response.v3.BuildResponse;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.inject.Inject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.jspecify.annotations.NullMarked;

@NullMarked
@UntrackedTask(because = "PublishToFillTask should always run when requested")
public abstract class PublishToFillTask extends DefaultTask implements AutoCloseable {
  public static final String NAME = "publishToFill";
  private static final String USER_AGENT = "Fill (Gradle Plugin)";
  private final HttpClient httpClient = HttpClient.newBuilder()
    .build();

  public PublishToFillTask() {
    this.setGroup("fill");
    this.setDescription("Publish to Fill");
  }

  @Nested
  public abstract Property<FillExtension> getExtension();

  @Inject
  public abstract ProjectLayout getProjectLayout();

  private void withGit(final Consumer<Git> consumer) {
    final File settingsDir = this.getProjectLayout().getSettingsDirectory().getAsFile();
    try (final Git git = Git.open(settingsDir)) {
      consumer.accept(git);
    } catch (final IOException e) {
      throw new GradleException("Failed to open git repository", e);
    }
  }

  @TaskAction
  public void run() {
    this.withGit(this::runWithGit);
  }

  private void runWithGit(final Git git) {
    final FillExtension extension = this.getExtension().get();

    final String project = extension.getProject().get();
    final String familyId = extension.getVersionFamily().get();
    final String versionId = extension.getVersion().get();
    final FillExtension.Build build = extension.getBuild();
    final int buildId = build.getId().get();
    final Instant time = Instant.now();

    final List<Commit> commits = new ArrayList<>();
    try (final RevWalk revWalk = new RevWalk(git.getRepository())) {
      final RevCommit currentCommit = revWalk.parseCommit(git.getRepository().exactRef(Constants.HEAD).getObjectId());
      revWalk.markStart(currentCommit);

      final List<BuildResponse> builds = this.getBuilds(extension);
      if (!builds.isEmpty()) {
        final BuildResponse lastBuild = builds.getFirst();
        final Commit lastCommit = lastBuild.commits().getFirst();
        final RevCommit lastBuildCommit = revWalk.parseCommit(git.getRepository().resolve(lastCommit.sha()));
        revWalk.markUninteresting(lastBuildCommit);
      }

      for (final RevCommit commit : revWalk) {
        commits.add(new Commit(
          commit.getName(),
          commit.getAuthorIdent().getWhen().toInstant(),
          commit.getFullMessage()
        ));
      }
    } catch (final IOException e) {
      throw new GradleException("Failed to get commit data", e);
    }

    final UUID id = UUID.randomUUID();
    final List<HttpRequest> requests = new ArrayList<>();
    try {
      final Map<String, Download> downloads = new HashMap<>();

      for (final FillExtension.Download download : build.getDownloads()) {
        final String key = download.getName();
        final String name = download.getNameResolver().get().name(project, familyId, versionId, buildId);
        final Path path = download.getFile().get().getAsFile().toPath();

        final byte[] content = Files.readAllBytes(path);
        final String sha256 = Hashing.sha256().hashBytes(content).toString();
        final int size = (int) Files.size(path);
        downloads.put(key, new Download(name, new Checksums(sha256), size));

        final HttpRequest.Builder builder = HttpRequest.newBuilder();
        builder.header("User-Agent", USER_AGENT);
        builder.header("Content-Type", "multipart/form-data; boundary=boundary");
        builder.uri(URI.create(extension.getApiUrl().get() + "/upload"));

        final List<byte[]> requestParts = new ArrayList<>();
        requestParts.add(("--boundary\r\nContent-Disposition: form-data; name=\"request\"\r\nContent-Type: application/json\r\n\r\n{\"id\":\"" + id + "\"}\r\n").getBytes(StandardCharsets.UTF_8));
        requestParts.add(("--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        requestParts.add(content);
        requestParts.add(("\r\n--boundary").getBytes(StandardCharsets.UTF_8));

        builder.POST(HttpRequest.BodyPublishers.ofByteArrays(requestParts));

        if (extension.getApiToken().isPresent()) {
          builder.header("Authorization", extension.getApiToken().get());
        } else {
          throw new GradleException("Api token is not present.");
        }

        requests.add(builder.build());
      }

      for (final HttpRequest request : requests) {
        try {
          final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          if (response.statusCode() != 200) {
            throw new GradleException("Failed to post data to the API: " + response.statusCode() + ": " + response.body());
          }
        } catch (final Exception e) {
          throw new GradleException("Failed to post data to the API", e);
        }
      }

      final PublishRequest request = new PublishRequest(
        id,
        project,
        familyId,
        versionId,
        buildId,
        time,
        build.getChannel().get(),
        commits.reversed(),
        downloads
      );

      try {
        final HttpRequest.Builder builder = HttpRequest.newBuilder();
        builder.header("User-Agent", USER_AGENT);
        builder.header("Content-Type", "application/json");
        builder.uri(URI.create(extension.getApiUrl().get() + "/publish"));
        builder.POST(HttpRequest.BodyPublishers.ofString(FillPlugin.MAPPER.writeValueAsString(request)));
        if (extension.getApiToken().isPresent()) {
          builder.headers("Authorization", extension.getApiToken().get());
        } else {
          throw new GradleException("Api token is not present.");
        }

        final HttpRequest post = builder.build();
        final HttpResponse<String> response = this.httpClient.send(post, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
          throw new GradleException("Failed to post data to the API: " + response.statusCode() + ": " + response.body());
        }
      } catch (final Exception e) {
        throw new GradleException("Failed to post data to the API", e);
      }
    } catch (final JsonProcessingException e) {
      throw new GradleException("Failed to serialize json", e);
    } catch (final IOException e) {
      throw new GradleException("Failed to read file", e);
    }
  }

  private List<BuildResponse> getBuilds(final FillExtension extension) {
    final String url = "%s/v3/projects/%s/versions/%s/builds".formatted(
      extension.getApiUrl().get(),
      extension.getProject().get(),
      extension.getVersion().get()
    );
    try {

      final HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", USER_AGENT).build();
      HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      final int statusCode = response.statusCode();
      if (statusCode == 200) {
        final String json = response.body();
        @SuppressWarnings("Convert2Diamond") final TypeReference<List<BuildResponse>> type = new TypeReference<List<BuildResponse>>() {
        };
        return FillPlugin.MAPPER.readValue(json, type);
      } else {
        throw new IOException("Unexpected response status: " + statusCode);
      }
    } catch (final IOException | InterruptedException e) {
      throw new GradleException("Failed to fetch latest build data for version " + extension.getVersion().get(), e);
    }
  }

  @Override
  public void close() {
    this.httpClient.close();
  }
}
