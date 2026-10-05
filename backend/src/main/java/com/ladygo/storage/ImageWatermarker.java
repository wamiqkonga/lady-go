package com.ladygo.storage;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;

/** Overlays a short text watermark on a JPEG/PNG image (java.awt only, no new dependency). */
public final class ImageWatermarker {

    private ImageWatermarker() {
    }

    public static byte[] withTextWatermark(byte[] original, String text) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(original));
            if (image == null) {
                throw new FileStorageException(new IOException("Not a readable image"));
            }
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setFont(new Font("SansSerif", Font.BOLD, Math.max(12, image.getWidth() / 20)));
            int margin = 10;
            int y = image.getHeight() - margin;
            g.setColor(new Color(0, 0, 0, 160));
            g.fillRect(0, y - g.getFontMetrics().getHeight(), image.getWidth(), g.getFontMetrics().getHeight() + margin);
            g.setColor(Color.WHITE);
            g.drawString(text, margin, y - 2);
            g.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new FileStorageException(ex);
        }
    }

    public static InputStream asStream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }
}
