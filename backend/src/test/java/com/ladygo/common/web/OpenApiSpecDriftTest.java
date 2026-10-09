package com.ladygo.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ladygo.testsupport.IntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Fails the build if the API's actual shape (every {@code @RequestMapping} in the running app,
 * springdoc-generated) has drifted from the spec checked in at {@code docs/api/openapi.json}. Both
 * sides are canonicalised (object keys sorted recursively, array order preserved) before comparing, so
 * this only catches a real shape change, never Jackson/springdoc's own non-deterministic key ordering.
 *
 * <p>To regenerate after an intentional API change: run the app, {@code curl localhost:8080/v3/api-docs
 * > docs/api/openapi.json}, and commit the result. This test canonicalises both sides before
 * comparing, so the checked-in file's exact formatting doesn't matter.
 */
@IntegrationTest
@AutoConfigureMockMvc
class OpenApiSpecDriftTest {

    private static final Path SPEC_PATH = Path.of("..", "docs", "api", "openapi.json");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void checkedInSpecMatchesTheRunningApp() throws Exception {
        String liveJson = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String checkedInJson = Files.readString(SPEC_PATH);

        String liveCanonical = canonicalize(liveJson);
        String checkedInCanonical = canonicalize(checkedInJson);

        assertThat(liveCanonical)
                .as("docs/api/openapi.json is out of date - regenerate it (see this test's javadoc) and commit the result")
                .isEqualTo(checkedInCanonical);
    }

    private String canonicalize(String json) throws IOException {
        JsonNode node = objectMapper.readTree(json);
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sort(node));
    }

    private JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> ordered = new TreeMap<>();
            node.fields().forEachRemaining(entry -> ordered.put(entry.getKey(), entry.getValue()));
            for (Map.Entry<String, JsonNode> entry : ordered.entrySet()) {
                sorted.set(entry.getKey(), sort(entry.getValue()));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = objectMapper.createArrayNode();
            node.forEach(element -> sorted.add(sort(element)));
            return sorted;
        }
        return node;
    }
}
