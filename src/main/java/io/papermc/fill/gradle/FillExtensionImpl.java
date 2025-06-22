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
import javax.inject.Inject;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class FillExtensionImpl implements FillExtension {
  private final Property<String> apiUrl;
  private final Property<String> apiToken;
  private final Property<String> project;
  private final Property<String> family;
  private final Property<String> version;
  private final Build build;

  @Inject
  public FillExtensionImpl(final ObjectFactory objects, final ProviderFactory providers) {
    this.apiUrl = objects.property(String.class).convention(providers.environmentVariable("FILL_API_URL"));
    this.apiToken = objects.property(String.class).convention(providers.environmentVariable("FILL_API_KEY"));
    this.project = objects.property(String.class);
    this.family = objects.property(String.class);
    this.version = objects.property(String.class);
    this.build = objects.newInstance(BuildImpl.class);
  }

  @Override
  public Property<String> getApiUrl() {
    return this.apiUrl;
  }

  @Override
  public Property<String> getApiToken() {
    return this.apiToken;
  }

  @Override
  public Property<String> getProject() {
    return this.project;
  }

  @Override
  public Property<String> getVersionFamily() {
    return this.family;
  }

  @Override
  public Property<String> getVersion() {
    return this.version;
  }

  @Override
  public Build getBuild() {
    return this.build;
  }

  @NullMarked
  public static class BuildImpl implements Build {
    private final Property<Integer> id;
    private final Property<BuildChannel> channel;
    private final NamedDomainObjectContainer<Download> downloads;

    @Inject
    public BuildImpl(final ObjectFactory objects, final ProviderFactory providers) {
      this.id = objects.property(Integer.class).convention(providers.environmentVariable("BUILD_NUMBER").map(Integer::parseInt));
      this.channel = objects.property(BuildChannel.class).convention(BuildChannel.STABLE);
      this.downloads = objects.domainObjectContainer(Download.class);
    }

    @Override
    public Property<Integer> getId() {
      return this.id;
    }

    @Override
    public Property<BuildChannel> getChannel() {
      return this.channel;
    }

    @Override
    public NamedDomainObjectContainer<Download> getDownloads() {
      return this.downloads;
    }
  }
}
