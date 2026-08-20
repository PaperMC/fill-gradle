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
import io.papermc.fill.model.request.v3.PublishRequest;
import io.papermc.fill.model.request.v3.StageRequest;
import io.papermc.fill.model.response.v3.BuildResponse;
import io.papermc.fill.model.response.v3.StageResponse;
import io.papermc.fill.model.response.v3.VersionResponse;
import io.papermc.fill.model.response.v3.VersionsResponse;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.inject.Inject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
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
  private static final String APPLICATION_JAVA_ARCHIVE = "application/java-archive";
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
    final int buildNumber = build.getId().get();
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

    if (!extension.getApiToken().isPresent()) {
      throw new GradleException("API token is not present");
    }
    final String apiToken = extension.getApiToken().get();
    final UUID id = UUID.randomUUID();
    final Map<String, Download> downloads = new HashMap<>();
    final List<PendingUpload> uploads = new ArrayList<>();
    try {
      for (final FillExtension.Download download : build.getDownloads()) {
        final String key = download.getName();
        final String name = download.getNameResolver().get().name(project, familyId, versionId, buildNumber);
        final Path path = download.getFile().get().getAsFile().toPath();
        final byte[] content = Files.readAllBytes(path);
        final String sha256 = Hashing.sha256().hashBytes(content).toString();
        final int size = content.length;
        final Download requestDownload = new Download(name, new Checksums(sha256), size);
        downloads.put(key, requestDownload);
        uploads.add(new PendingUpload(requestDownload, content, APPLICATION_JAVA_ARCHIVE, contentMd5(content)));
      }

      for (final PendingUpload upload : uploads) {
        final URI uploadUrl = this.requestUploadUrl(extension, apiToken, id, upload);
        this.upload(uploadUrl, upload);
      }

      final PublishRequest request = new PublishRequest(
        id,
        project,
        familyId,
        versionId,
        buildNumber,
        time,
        build.getChannel().get(),
        commits.reversed(),
        downloads
      );
      this.publish(extension, apiToken, request);
    } catch (final JsonProcessingException e) {
      throw new GradleException("Failed to serialize JSON", e);
    } catch (final IOException e) {
      throw new GradleException("Failed to publish files", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GradleException("Publishing was interrupted", e);
    }
  }

  private static String apiUrl(final FillExtension extension) {
    final String url = extension.getApiUrl().get();
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private URI requestUploadUrl(
    final FillExtension extension,
    final String apiToken,
    final UUID id,
    final PendingUpload upload
  ) throws IOException, InterruptedException {
    final StageRequest request = new StageRequest(id, upload.download(), upload.contentType(), upload.contentMd5());
    final HttpRequest httpRequest = HttpRequest.newBuilder()
      .uri(URI.create(apiUrl(extension) + "/v3/publishing/stage"))
      .header("Authorization", apiToken)
      .header("Content-Type", "application/json")
      .header("User-Agent", USER_AGENT)
      .POST(HttpRequest.BodyPublishers.ofString(MapperHolder.MAPPER.writeValueAsString(request)))
      .build();
    final HttpResponse<String> response = this.httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("Failed to create upload URL: " + response.statusCode() + ": " + response.body());
    }
    return MapperHolder.MAPPER.readValue(response.body(), StageResponse.class).url();
  }

  private void upload(final URI uploadUrl, final PendingUpload upload) throws IOException, InterruptedException {
    final HttpRequest request = HttpRequest.newBuilder()
      .uri(uploadUrl)
      .header("Content-MD5", upload.contentMd5())
      .header("Content-Type", upload.contentType())
      .header("x-amz-meta-sha256", upload.download().checksums().sha256())
      .PUT(HttpRequest.BodyPublishers.ofByteArray(upload.content()))
      .build();
    final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new IOException("Failed to upload file: " + response.statusCode() + ": " + response.body());
    }
  }

  private void publish(
    final FillExtension extension,
    final String apiToken,
    final PublishRequest request
  ) throws IOException, InterruptedException {
    final HttpRequest httpRequest = HttpRequest.newBuilder()
      .uri(URI.create(apiUrl(extension) + "/v3/publishing/publish"))
      .header("Authorization", apiToken)
      .header("Content-Type", "application/json")
      .header("User-Agent", USER_AGENT)
      .POST(HttpRequest.BodyPublishers.ofString(MapperHolder.MAPPER.writeValueAsString(request)))
      .build();
    final HttpResponse<String> response = this.httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 201) {
      throw new IOException("Failed to publish build: " + response.statusCode() + ": " + response.body());
    }
  }

  private static String contentMd5(final byte[] content) {
    try {
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(content));
    } catch (final NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private record PendingUpload(Download download, byte[] content, String contentType, String contentMd5) {
  }

  private List<Commit> gatherCommits(Git git, FillExtension extension) {
    final List<Commit> commits = new ArrayList<>();
    try (final RevWalk revWalk = new RevWalk(git.getRepository())) {
      final RevCommit currentCommit = revWalk.parseCommit(git.getRepository().exactRef(Constants.HEAD).getObjectId());
      revWalk.markStart(currentCommit);

      final List<BuildResponse> builds = this.fetchPreviousBuilds(extension);
      if (!builds.isEmpty()) {
        if (hasLatestBuildWithEmptyCommits(builds)) {
          return commits;
        }

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
          if (!this.tryMarkPreviousBuildCommit(git, revWalk, currentCommit, lastCommit)) {
            return commits;
          }
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

  @VisibleForTesting
  static boolean hasLatestBuildWithEmptyCommits(final List<BuildResponse> builds) {
    return !builds.isEmpty() && builds.getFirst().commits().isEmpty();
  }

  private boolean tryMarkPreviousBuildCommit(final Git git, final RevWalk revWalk, final RevCommit currentCommit, final Commit lastCommit) throws IOException {
    final ObjectId lastBuildObjectId = git.getRepository().resolve(lastCommit.sha());
    if (lastBuildObjectId == null) {
      this.logEmptyChangelogWarning(
        lastCommit.sha(),
        currentCommit.getName(),
        "previous build commit is not present in the local repository",
        false
      );
      return false;
    }

    try (final RevWalk ancestryWalk = new RevWalk(git.getRepository())) {
      final RevCommit previousBuildCommit = ancestryWalk.parseCommit(lastBuildObjectId);
      final RevCommit currentBuildCommit = ancestryWalk.parseCommit(currentCommit);
      if (!ancestryWalk.isMergedInto(previousBuildCommit, currentBuildCommit)) {
        this.logEmptyChangelogWarning(
          lastCommit.sha(),
          currentCommit.getName(),
          "previous build commit is not an ancestor of the current HEAD",
          false
        );
        return false;
      }
    } catch (final MissingObjectException e) {
      // looks like history was squashed away, lets just use this commit as changelog
      revWalk.markUninteresting(revWalk.parseCommit(currentCommit.getParent(0)));
      this.logEmptyChangelogWarning(
        lastCommit.sha(),
        currentCommit.getName(),
        "previous build commit could not be loaded from the local repository",
        true
      );
      return true;
    }

    revWalk.markUninteresting(revWalk.parseCommit(lastBuildObjectId));
    return true;
  }

  private void logEmptyChangelogWarning(final String previousCommit, final String currentCommit, final String reason, final boolean shortChangelog) {
    this.getLogger().warn(
      "Unable to compute changelog: {} (previous build commit: {}, current HEAD: {}). {}",
      reason,
      previousCommit,
      currentCommit,
      shortChangelog ? "Publishing with short changelog" : "Publishing with an empty changelog."
    );
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
      apiUrl(extension),
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
      apiUrl(extension),
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
