package com.example.server.service.ai.impl;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

public final class FrameDifferenceHash {

    private FrameDifferenceHash() {
    }

    public static long calculate(Path imagePath) {
        try {
            BufferedImage source = ImageIO.read(imagePath.toFile());
            if (source == null) {
                throw new IllegalArgumentException("Unsupported keyframe image: " + imagePath);
            }

            BufferedImage resized = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D graphics = resized.createGraphics();
            graphics.drawImage(source, 0, 0, 9, 8, null);
            graphics.dispose();

            long hash = 0L;
            int bit = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    if (resized.getRGB(x, y) > resized.getRGB(x + 1, y)) {
                        hash |= 1L << bit;
                    }
                    bit++;
                }
            }
            return hash;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read keyframe image", exception);
        }
    }

    public static boolean similar(long left, long right) {
        return Long.bitCount(left ^ right) <= 5;
    }
}
