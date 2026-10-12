package com.example.server.service.ai.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FrameDifferenceHashTest {

    @TempDir
    Path directory;

    @Test
    void detectsEqualAndOppositeHorizontalGradients() throws Exception {
        Path ascending = writeGradient("ascending.png", false);
        Path same = writeGradient("same.png", false);
        Path descending = writeGradient("descending.png", true);

        long ascendingHash = FrameDifferenceHash.calculate(ascending);

        assertThat(FrameDifferenceHash.similar(ascendingHash, FrameDifferenceHash.calculate(same))).isTrue();
        assertThat(FrameDifferenceHash.similar(ascendingHash, FrameDifferenceHash.calculate(descending))).isFalse();
    }

    private Path writeGradient(String filename, boolean reverse) throws Exception {
        BufferedImage image = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int value = (reverse ? 8 - x : x) * 31;
                image.setRGB(x, y, new Color(value, value, value).getRGB());
            }
        }
        Path path = directory.resolve(filename);
        ImageIO.write(image, "png", path.toFile());
        return path;
    }
}
