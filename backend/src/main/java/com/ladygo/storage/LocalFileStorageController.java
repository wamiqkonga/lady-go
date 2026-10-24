package com.ladygo.storage;

import com.ladygo.common.exception.ResourceNotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves {@link LocalFileStorage}'s dev/test "presigned URLs" - deliberately public (no JWT), since a
 * real cloud presigned URL also bypasses the app's normal auth; the HMAC signature and expiry are the
 * only gate, exactly like a real one (docs/api/driver-onboarding.md section 1). Listed in
 * {@code SecurityConfig.PUBLIC_ENDPOINTS}.
 */
@RestController
@Profile({"dev", "test"})
public class LocalFileStorageController {

    private final LocalFileStorage storage;

    public LocalFileStorageController(LocalFileStorage storage) {
        this.storage = storage;
    }

    @GetMapping("/internal/files")
    public ResponseEntity<byte[]> serve(
            @RequestParam String key, @RequestParam("exp") long expiryEpochSeconds, @RequestParam String sig) {
        if (!storage.isSignatureValid(key, expiryEpochSeconds, sig)) {
            throw new ResourceNotFoundException();
        }
        byte[] content = storage.read(key);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(storage.contentType(key)))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content);
    }
}
