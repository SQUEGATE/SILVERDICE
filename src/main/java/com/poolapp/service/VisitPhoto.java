package com.poolapp.service;

import com.poolapp.model.VisitRecord;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Iterator;

public final class VisitPhoto {
    private VisitPhoto() {}

    public static String fromFile(Path file) throws IOException {
        if (Files.size(file) > 25 * 1024 * 1024) throw new IOException("Choose a photo smaller than 25 MB.");
        BufferedImage image;
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) throw new IOException("Unable to open the photo.");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Choose a PNG or JPEG photo.");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!"png".equalsIgnoreCase(format) && !"jpeg".equalsIgnoreCase(format)) {
                    throw new IOException("Choose a PNG or JPEG photo.");
                }
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 100_000_000) {
                    throw new IOException("Photo dimensions are too large.");
                }
                var param = reader.getDefaultReadParam();
                int sample = Math.max(1, Math.max(width, height) / 1280);
                param.setSourceSubsampling(sample, sample, 0, 0);
                image = reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
        if (image == null) throw new IOException("Unable to read the photo.");
        return encode(image);
    }

    private static String encode(BufferedImage source) throws IOException {
        int longest = Math.min(1280, Math.max(source.getWidth(), source.getHeight()));
        for (int attempt = 0; attempt < 5; attempt++, longest = Math.max(160, longest * 3 / 4)) {
            double scale = Math.min(1.0, (double) longest / Math.max(source.getWidth(), source.getHeight()));
            BufferedImage resized = new BufferedImage(Math.max(1, (int) (source.getWidth() * scale)),
                    Math.max(1, (int) (source.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = resized.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, resized.getWidth(), resized.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, resized.getWidth(), resized.getHeight(), null);
            graphics.dispose();
            for (float quality : new float[]{0.85f, 0.7f, 0.5f}) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                try (var output = new MemoryCacheImageOutputStream(bytes)) {
                    writer.setOutput(output);
                    ImageWriteParam params = writer.getDefaultWriteParam();
                    params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    params.setCompressionQuality(quality);
                    writer.write(null, new javax.imageio.IIOImage(resized, null, null), params);
                } finally {
                    writer.dispose();
                }
                String data = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
                if (data.length() <= VisitRecord.MAX_PHOTO_LENGTH) return data;
            }
        }
        throw new IOException("Unable to shrink the photo enough to save. Choose a smaller photo.");
    }

    public static BufferedImage preview(String data) throws IOException {
        try {
            byte[] bytes = Base64.getDecoder().decode(data.substring(data.indexOf(',') + 1));
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                if (input == null) throw new IOException("Unable to read saved visit photo.");
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException("Saved visit photo is not a readable image.");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || (long) width * height > 100_000_000) {
                        throw new IOException("Saved visit photo dimensions are too large.");
                    }
                    var params = reader.getDefaultReadParam();
                    int sample = Math.max(1, Math.max(width, height) / 400);
                    params.setSourceSubsampling(sample, sample, 0, 0);
                    return reader.read(0, params);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Saved visit photo contains invalid image data.", e);
        }
    }
}
