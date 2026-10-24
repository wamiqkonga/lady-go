package com.ladygo.storage;

import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import com.ladygo.common.exception.ValidationException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Shared content-type/size/pixel checks for every document/selfie upload endpoint (adversarial-review
 * finding, CRITICAL: a declared {@code Content-Type} alone was previously trusted with no check that
 * the bytes are actually a decodable image of that kind, and the eventual watermarking step
 * ({@link ImageWatermarker}) fully decodes whatever is stored, uncapped - a crafted file with huge
 * header dimensions and a small compressed payload is a classic decompression-bomb DoS. Reading only
 * {@code ImageReader#getWidth}/{@code getHeight} here reads the image header, not the full raster, so
 * this check itself cannot be used as the same bomb).
 */
public final class UploadValidator {

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png");

    /** 16 megapixels - comfortably above any phone camera selfie or document photo, far below anything
     *  that would make {@link ImageWatermarker}'s full in-memory decode a meaningful DoS vector. */
    private static final long MAX_PIXELS = 16_000_000L;

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
        validateActuallyDecodableAndWithinPixelLimit(file);
    }

    private static void validateActuallyDecodableAndWithinPixelLimit(MultipartFile file) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(file.getInputStream())) {
            if (iis == null) {
                throw new ValidationException("File is not a readable image.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new ValidationException("File is not a readable image.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                long pixels = (long) reader.getWidth(0) * (long) reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw new ValidationException("Image dimensions exceed the maximum allowed.");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            throw new ValidationException("File is not a readable image.");
        }
    }
}
