package com.ladygo.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageWatermarkerTest {

    private static byte[] png(int w, int h, int type) throws Exception {
        BufferedImage image = new BufferedImage(w, h, type);
        Random rnd = new Random(7);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, (0xFF << 24) | (Math.min(255, x * 255 / w + rnd.nextInt(16)) << 16)
                        | (Math.min(255, y * 255 / h + rnd.nextInt(16)) << 8) | rnd.nextInt(64));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void largeLandscapePhotoBecomesASmallBoundedDecodableJpeg() throws Exception {
        byte[] out = ImageWatermarker.withTextWatermarkJpeg(png(2000, 1500, BufferedImage.TYPE_INT_RGB), "drv", 1024, 300 * 1024);

        assertThat(out[0] & 0xFF).isEqualTo(0xFF);
        assertThat(out[1] & 0xFF).isEqualTo(0xD8);
        assertThat(out.length).isLessThanOrEqualTo(300 * 1024);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out));
        assertThat(decoded.getWidth()).isEqualTo(1024);
        assertThat(decoded.getHeight()).isEqualTo(768);
    }

    @Test
    void portraitIsBoundedByItsHeightAndSmallImagesAreNeverUpscaled() throws Exception {
        BufferedImage tall = ImageIO.read(new ByteArrayInputStream(ImageWatermarker.withTextWatermarkJpeg(
                png(1500, 2000, BufferedImage.TYPE_INT_RGB), "drv", 1024, 300 * 1024)));
        assertThat(tall.getHeight()).isEqualTo(1024);
        assertThat(tall.getWidth()).isEqualTo(768);

        BufferedImage small = ImageIO.read(new ByteArrayInputStream(ImageWatermarker.withTextWatermarkJpeg(
                png(200, 100, BufferedImage.TYPE_INT_RGB), "drv", 1024, 300 * 1024)));
        assertThat(small.getWidth()).isEqualTo(200);
        assertThat(small.getHeight()).isEqualTo(100);
    }

    @Test
    void anImageWithAnAlphaChannelStillEncodesAsJpeg() throws Exception {
        byte[] out = ImageWatermarker.withTextWatermarkJpeg(png(300, 300, BufferedImage.TYPE_INT_ARGB), "drv", 1024, 300 * 1024);
        assertThat(ImageIO.read(new ByteArrayInputStream(out))).isNotNull();
    }

    @Test
    void theWatermarkBandIsDrawnOverTheBottomOfTheImage() throws Exception {
        BufferedImage white = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = white.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 400, 400);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(white, "png", png);

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(
                ImageWatermarker.withTextWatermarkJpeg(png.toByteArray(), "driver-id", 1024, 300 * 1024)));

        int topPixel = out.getRGB(200, 5) & 0xFF;
        int bandPixel = out.getRGB(390, 395) & 0xFF; // inside the translucent dark band, away from the text
        assertThat(topPixel).isGreaterThan(240);
        assertThat(bandPixel).isLessThan(200);
    }
}
