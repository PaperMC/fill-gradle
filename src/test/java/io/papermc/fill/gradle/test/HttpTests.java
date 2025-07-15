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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.papermc.fill.gradle.task.PublishToFillTask;
import io.papermc.fill.model.response.v3.BuildResponse;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class HttpTests {

    @Test
    @Disabled // TODO
    public void test() throws InterruptedException, IOException {
        String urlString = "http://localhost:3002/v3/projects/paper/versions/1.21.1/builds"; // TODO: Replace with actual URL later
        String jsonResponse = fetchUrlContent(urlString);
        assertNotNull(jsonResponse, "Fetched content should not be null");

        ObjectMapper objectMapper = PublishToFillTask.MapperHolder.MAPPER;
        List<BuildResponse> builds = objectMapper.readValue(jsonResponse, new TypeReference<>() {});
        assertNotNull(builds, "Parsed BuildsResponse should not be null");
    }

    private String fetchUrlContent(String urlString) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlString))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}
