package com.ladygo.storage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.ladygo.common.exception.ResourceNotFoundException;

/** Verifies the dev/test "presigned URL" is actually expiry- and signature-checked (adversarial-review-style coverage). */
class LocalFileStorageControllerTest {

    private static final String SECRET = "test-only-local-storage-secret-at-least-32-bytes-long";

    private MockMvc mockMvc;
    private LocalFileStorage storage;

    @BeforeEach
    void setUp(@TempDir java.nio.file.Path dir) {
        storage = new LocalFileStorage(dir.toString(), SECRET, "http://localhost:8080");
        storage.upload("k1", new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), 5, "text/plain");
        mockMvc = MockMvcBuilders.standaloneSetup(new LocalFileStorageController(storage))
                .setControllerAdvice(new NotFoundAdvice())
                .build();
    }

    @RestControllerAdvice
    static class NotFoundAdvice {
        @ExceptionHandler(ResourceNotFoundException.class)
        org.springframework.http.ResponseEntity<Void> handle() {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
    }

    @Test
    void validSignatureServesTheFile() throws Exception {
        Map<String, String> params = queryParams(storage.presignedDownloadUrl("k1", Duration.ofMinutes(5)));

        mockMvc.perform(get("/internal/files")
                        .param("key", params.get("key"))
                        .param("exp", params.get("exp"))
                        .param("sig", params.get("sig")))
                .andExpect(status().isOk())
                .andExpect(content().string("hello"));
    }

    @Test
    void expiredSignatureIsRejected() throws Exception {
        Map<String, String> params = queryParams(storage.presignedDownloadUrl("k1", Duration.ofMinutes(5)));
        long alreadyExpired = System.currentTimeMillis() / 1000 - 10;

        mockMvc.perform(get("/internal/files")
                        .param("key", params.get("key"))
                        .param("exp", String.valueOf(alreadyExpired))
                        .param("sig", params.get("sig")))
                .andExpect(status().isNotFound());
    }

    @Test
    void tamperedSignatureIsRejected() throws Exception {
        Map<String, String> params = queryParams(storage.presignedDownloadUrl("k1", Duration.ofMinutes(5)));

        mockMvc.perform(get("/internal/files")
                        .param("key", params.get("key"))
                        .param("exp", params.get("exp"))
                        .param("sig", "0000000000000000000000000000000000000000000000000000000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void wrongKeyWithAValidSignatureForAnotherKeyIsRejected() throws Exception {
        storage.upload("k2", new ByteArrayInputStream("secret".getBytes(StandardCharsets.UTF_8)), 6, "text/plain");
        Map<String, String> paramsForK1 = queryParams(storage.presignedDownloadUrl("k1", Duration.ofMinutes(5)));

        mockMvc.perform(get("/internal/files")
                        .param("key", "k2")
                        .param("exp", paramsForK1.get("exp"))
                        .param("sig", paramsForK1.get("sig")))
                .andExpect(status().isNotFound());
    }

    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> out = new HashMap<>();
        for (String pair : uri.getQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            out.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return out;
    }
}
