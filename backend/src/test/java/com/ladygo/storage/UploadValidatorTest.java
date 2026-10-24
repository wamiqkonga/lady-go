package com.ladygo.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.ladygo.common.exception.ValidationException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Adversarial review findings (CRITICAL/LOW): a declared {@code Content-Type} was previously trusted
 * with no check that the bytes are actually a decodable image of that kind - a crafted file with huge
 * header dimensions and a tiny compressed payload would sail through size/content-type checks, then
 * get fully decoded uncapped by {@link ImageWatermarker} later on the rider-selfie delivery path
 * (a decompression-bomb DoS). These tests fail if {@code UploadValidator.validateImage} goes back to
 * checking only size and the declared {@code Content-Type}.
 */
class UploadValidatorTest {

    private static final long GENEROUS_MAX_SIZE = 10_000_000;

    private static byte[] realJpegBytes(int size) throws Exception {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void aRealSmallJpegPassesValidation() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "ok.jpg", "image/jpeg", realJpegBytes(20));
        assertThatNoException().isThrownBy(() -> UploadValidator.validateImage(file, GENEROUS_MAX_SIZE));
    }

    /** Content-type spoofing: the declared type says image/jpeg, but the bytes are plain text. */
    @Test
    void bytesThatAreNotActuallyAnImageAreRejectedDespiteAnImageContentType() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "ok.jpg", "image/jpeg", "<html>not an image</html>".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> UploadValidator.validateImage(file, GENEROUS_MAX_SIZE))
                .isInstanceOf(ValidationException.class);
    }

    /**
     * A hand-built PNG with a correctly-signed {@code IHDR} declaring 50000x50000 pixels (2.5 billion -
     * comfortably past {@code UploadValidator}'s 16-megapixel cap) and no pixel data at all - the same
     * shape a real decompression-bomb file takes (tiny on disk, huge once decoded). The old
     * (pre-fix) validator never looked past the declared Content-Type and size, so this would have
     * passed straight through to {@link ImageWatermarker}'s full, uncapped decode.
     */
    @Test
    void anImageWithAnEnormousDeclaredPixelCountIsRejectedBeforeAnyFullDecode() {
        byte[] bombPng = pngWithDeclaredDimensions(50_000, 50_000);
        MockMultipartFile file = new MockMultipartFile("file", "bomb.png", "image/png", bombPng);

        assertThatThrownBy(() -> UploadValidator.validateImage(file, GENEROUS_MAX_SIZE))
                .isInstanceOf(ValidationException.class);
    }

    private static byte[] pngWithDeclaredDimensions(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}); // PNG signature

        ByteArrayOutputStream ihdrData = new ByteArrayOutputStream();
        writeInt(ihdrData, width);
        writeInt(ihdrData, height);
        ihdrData.write(8); // bit depth
        ihdrData.write(2); // color type: truecolor
        ihdrData.write(0); // compression
        ihdrData.write(0); // filter
        ihdrData.write(0); // interlace
        writeChunk(out, "IHDR", ihdrData.toByteArray());

        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        writeInt(out, data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
