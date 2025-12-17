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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.hash.Hashing;
import io.papermc.fill.gradle.FillExtension;
import io.papermc.fill.model.Checksums;
import io.papermc.fill.model.Commit;
import io.papermc.fill.model.Download;
import io.papermc.fill.model.request.PublishRequest;
import io.papermc.fill.model.response.v3.BuildResponse;
import io.papermc.fill.model.response.v3.VersionResponse;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.inject.Inject;
import io.papermc.fill.model.response.v3.VersionsResponse;
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
import org.jetbrains.annotations.VisibleForTesting;
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
    final String timeString = this.getExtension().get().getBuildTimestamp().getOrNull();
    final Instant time;
    if (timeString != null) {
      try {
        time = Instant.parse(timeString);
      } catch (final DateTimeParseException e) {
        throw new GradleException("Failed to parse build timestamp: " + timeString, e);
      }
    } else {
      time = Instant.now();
    }

    final List<Commit> commits = this.gatherCommits(git, extension);

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
          throw new GradleException("API token is not present");
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
        final HttpRequest.Builder builder = HttpRequest.newBuilder()
          .uri(URI.create(extension.getApiUrl().get() + "/publish"))
          .header("Content-Type", "application/json")
          .header("User-Agent", USER_AGENT);
        builder.POST(HttpRequest.BodyPublishers.ofString(MapperHolder.MAPPER.writeValueAsString(request)));
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
        throw new GradleException("Failed to post data to the API: " + e.getMessage(), e);
      }
    } catch (final JsonProcessingException e) {
      throw new GradleException("Failed to serialize json", e);
    } catch (final IOException e) {
      throw new GradleException("Failed to read file", e);
    }
  }

  private List<Commit> gatherCommits(Git git, FillExtension extension) {
    final List<Commit> commits = new ArrayList<>();
    try (final RevWalk revWalk = new RevWalk(git.getRepository())) {
      final RevCommit currentCommit = revWalk.parseCommit(git.getRepository().exactRef(Constants.HEAD).getObjectId());
      revWalk.markStart(currentCommit);

      final List<BuildResponse> builds = this.fetchPreviousBuilds(extension);
      if (!builds.isEmpty()) {
        // not every build might have commits, we have to find the last one that did have some
        BuildResponse lastBuildWithCommits = null;
        for (final BuildResponse build : builds) {
          if (!build.commits().isEmpty()) {
            lastBuildWithCommits = build;
            break;
          }
        }

        if (lastBuildWithCommits != null) {
          final Commit lastCommit = lastBuildWithCommits.commits().getFirst();
          final RevCommit lastBuildCommit = revWalk.parseCommit(git.getRepository().resolve(lastCommit.sha()));
          revWalk.markUninteresting(lastBuildCommit);
        }
      }

      for (final RevCommit commit : revWalk) {
        commits.add(new Commit(
          commit.getName(),
          commit.getAuthorIdent().getWhenAsInstant(),
          commit.getFullMessage()
        ));
      }
    } catch (final IOException e) {
      throw new GradleException("Failed to get commit data", e);
    }
    return commits;
  }

  private List<BuildResponse> fetchPreviousBuilds(final FillExtension extension) {
    final String currentVersion = extension.getVersion().get();
    final VersionsResponse versions = this.getVersions(extension);

    // Check if the current version already has builds
    for (final VersionResponse version : versions.versions()) {
      if (version.version().id().equals(currentVersion) && !version.builds().isEmpty()) {
        return this.fetchCurrentVersionBuilds(extension, currentVersion);
      }
    }

    // For new versions without builds, fall back to finding the last version with builds
    return this.fetchLastVersionBuilds(extension, versions);
  }

  private List<BuildResponse> fetchCurrentVersionBuilds(final FillExtension extension, final String version) {
    return this.getBuilds(extension, version);
  }

  private List<BuildResponse> fetchLastVersionBuilds(final FillExtension extension, final VersionsResponse versions) {
    for (final VersionResponse version : versions.versions()) {
      if (!version.builds().isEmpty()) {
        return this.getBuilds(extension, version.version().id());
      }
    }
    return List.of();
  }

  private VersionsResponse getVersions(final FillExtension extension) {
    final String url = String.format(
      "%s/v3/projects/%s/versions",
      extension.getApiUrl().get(),
      extension.getProject().get()
    );
    try {
      final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("User-Agent", USER_AGENT)
        .build();
      final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      final int statusCode = response.statusCode();
      if (statusCode == 200) {
        final String json = response.body();
        return MapperHolder.MAPPER.readValue(json, VersionsResponse.class);
      } else {
        throw new IOException("Unexpected response status: " + statusCode);
      }
    } catch (final IOException | InterruptedException e) {
      throw new GradleException("Failed to fetch latest build data for version " + extension.getVersion().get() + ": " + e.getMessage(), e);
    }
  }

  private List<BuildResponse> getBuilds(final FillExtension extension, final String version) {
    final String url = String.format(
      "%s/v3/projects/%s/versions/%s/builds",
      extension.getApiUrl().get(),
      extension.getProject().get(),
      version
    );
    try {
      final HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("User-Agent", USER_AGENT)
        .build();
      final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      final int statusCode = response.statusCode();
      if (statusCode == 200) {
        final String json = response.body();
        @SuppressWarnings("Convert2Diamond")
        final TypeReference<List<BuildResponse>> type = new TypeReference<List<BuildResponse>>() {};
        return MapperHolder.MAPPER.readValue(json, type);
      } else {
        throw new IOException("Unexpected response status: " + statusCode);
      }
    } catch (final IOException | InterruptedException e) {
      throw new GradleException("Failed to fetch latest build data for version " + extension.getVersion().get() + ": " + e.getMessage(), e);
    }
  }

  @Override
  public void close() {
    this.httpClient.close();
  }

  @VisibleForTesting
  public static final class MapperHolder {
    public static final ObjectMapper MAPPER = new ObjectMapper()
      .registerModule(new JavaTimeModule());
  }
}
