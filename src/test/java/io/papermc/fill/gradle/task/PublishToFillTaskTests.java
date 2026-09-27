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

import io.papermc.fill.model.BuildChannel;
import io.papermc.fill.model.response.v3.BuildResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishToFillTaskTests {
  @Test
  void latestBuildWithEmptyCommitsReturnsTrue() {
    final BuildResponse latest = new BuildResponse(2, Instant.EPOCH, BuildChannel.STABLE, List.of(), Map.of());
    final BuildResponse older = new BuildResponse(1, Instant.EPOCH, BuildChannel.STABLE, List.of(), Map.of());

    assertTrue(PublishToFillTask.hasLatestBuildWithEmptyCommits(List.of(latest, older)));
  }

  @Test
  void latestBuildWithCommitsReturnsFalse() {
    final BuildResponse latest = new BuildResponse(2, Instant.EPOCH, BuildChannel.STABLE, List.of(
      new io.papermc.fill.model.Commit("abc", Instant.EPOCH, "msg")
    ), Map.of());

    assertFalse(PublishToFillTask.hasLatestBuildWithEmptyCommits(List.of(latest)));
  }

  @Test
  void emptyBuildListReturnsFalse() {
    assertFalse(PublishToFillTask.hasLatestBuildWithEmptyCommits(List.of()));
  }
}
