package com.ladygo.storage;

import java.util.Locale;
import java.util.Set;
import com.ladygo.common.exception.ValidationException;
import org.springframework.web.multipart.MultipartFile;

/** Shared content-type/size checks for every document/selfie upload endpoint (adversarial-review-style coverage). */
public final class UploadValidator {

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png");

    private UploadValidator() {
    }

    public static void validateImage(MultipartFile file, long maxSizeBytes) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("A file is required.");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new ValidationException("File exceeds the maximum allowed size of " + maxSizeBytes + " bytes.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new ValidationException("Only JPEG or PNG images are accepted.");
        }
    }
}
