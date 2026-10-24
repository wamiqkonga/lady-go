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
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Overlays a short text watermark on a JPEG/PNG image (java.awt only, no new dependency). */
public final class ImageWatermarker {

    /** Qualities tried in order until the output fits; the last one is used even if still over target. */
    private static final float[] JPEG_QUALITIES = {0.85f, 0.75f, 0.65f, 0.55f, 0.45f, 0.35f, 0.25f};

    private ImageWatermarker() {
    }

    /**
     * Mobile-friendly delivery form: downscales so the longest side is at most {@code maxSide} px (never
     * upscales), overlays the same text watermark as {@link #withTextWatermark} (drawn after the resize so
     * it stays legible), and encodes a JPEG, stepping the quality down until it is at most
     * {@code targetBytes} (best effort - the lowest quality is used if even that is larger).
     */
    public static byte[] withTextWatermarkJpeg(byte[] original, String text, int maxSide, int targetBytes) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(original));
            if (source == null) {
                throw new FileStorageException(new IOException("Not a readable image"));
            }
            BufferedImage image = scaledRgb(source, maxSide);
            drawWatermark(image, text);
            byte[] best = null;
            for (float quality : JPEG_QUALITIES) {
                best = encodeJpeg(image, quality);
                if (best.length <= targetBytes) {
                    break;
                }
            }
            return best;
        } catch (IOException ex) {
            throw new FileStorageException(ex);
        }
    }

    /** Opaque RGB copy (JPEG has no alpha), reduced so the longest side is at most {@code maxSide}. */
    private static BufferedImage scaledRgb(BufferedImage source, int maxSide) {
        int w = source.getWidth();
        int h = source.getHeight();
        double scale = Math.min(1.0, (double) maxSide / Math.max(w, h));
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, tw, th);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source.getScaledInstance(tw, th, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
        g.dispose();
        return out;
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static void drawWatermark(BufferedImage image, String text) {
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
    }

    public static byte[] withTextWatermark(byte[] original, String text) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(original));
            if (image == null) {
                throw new FileStorageException(new IOException("Not a readable image"));
            }
            drawWatermark(image, text);

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
