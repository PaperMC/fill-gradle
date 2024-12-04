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

import io.papermc.fill.model.BuildChannel;
import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface FillExtension {
  String NAME = "fill";

  @Input
  Property<String> getApiUrl();

  /**
   * Set the api url to interact with
   *
   * @param url the url
   */
  default void apiUrl(String url) {
    this.getApiUrl().set(url);
  }

  @Input
  Property<String> getApiToken();

  /**
   * Set the API authorization token
   *
   * @param token the token
   */
  default void apiToken(String token) {
    this.getApiToken().set(token);
  }

  @Input
  Property<String> getProject();

  /**
   * Set the project type to publish for
   *
   * @param project the type
   */
  default void project(String project) {
    this.getProject().set(project);
  }

  @Input
  Property<String> getVersionFamily();

  /**
   * Set the version family
   * <p>
   *   eg 1.20, 1.21, 1.22
   * </p>
   *
   * @param family the family
   */
  default void versionFamily(String family) {
    this.getVersionFamily().set(family);
  }

  /**
   * Set the version family
   * <p>
   *   eg 1.20, 1.21, 1.22
   * </p>
   *
   * @param provider the family provider
   */
  default void versionFamily(Provider<String> provider) {
    this.getVersionFamily().set(provider);
  }

  @Input
  Property<String> getVersion();

  /**
   * Set the minecraft version to publish
   *
   * @param version the version
   */
  default void version(String version) {
    this.getVersion().set(version);
  }

  /**
   * Set the minecraft version to publish
   *
   * @param provider the version provider
   */
  default void version(Provider<String> provider) {
    this.getVersion().set(provider);
  }

  @Nested
  Build getBuild();

  default void build(final Action<? super Build> action) {
    action.execute(this.getBuild());
  }

  @NullMarked
  interface Build {
    @Input
    Property<Integer> getId();

    @Input
    Property<BuildChannel> getChannel();

    @Nested
    NamedDomainObjectContainer<Download> getDownloads();

    default void downloads(final Action<? super NamedDomainObjectContainer<Download>> action) {
      action.execute(this.getDownloads());
    }
  }

  @NullMarked
  interface Download extends Named {
    @Input
    @Override
    String getName();

    @Input
    Property<NameResolver> getNameResolver();

    @InputFile
    RegularFileProperty getFile();

    @FunctionalInterface
    @NullMarked
    interface NameResolver {
      /**
       * Resolves the file name for the download.
       *
       * @param project the project name
       * @param family the version family name
       * @param version the version name
       * @param build the build number
       * @return the file name for the download
       */
      String name(
        final String project,
        final String family,
        final String version,
        final int build
      );
    }
  }
}
