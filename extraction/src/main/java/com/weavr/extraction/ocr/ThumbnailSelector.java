package com.weavr.extraction.ocr;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Ranks frames by sharpness, locally, with no model call. Ported unchanged
 * from {@code api}'s {@code ThumbnailSelector}.
 *
 * <p>Variance of the Laplacian is the standard cheap focus measure. Used here
 * to pick which frames a gate failure hands back as artifacts — a
 * motion-blurred frame costs the backend's vision tokens and returns nothing,
 * so rank, then take the top few.
 */
@Component
public class ThumbnailSelector {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailSelector.class);

    /**
     * Score multiplier at the very start of the clip; it rises linearly to 1.0
     * at the end. A 25% penalty reorders near-equals without letting a blurry
     * last frame beat a sharp first one.
     */
    private static final double EARLIEST_FRAME_WEIGHT = 0.75;

    /** @param score sharpness after the end-weighting, comparable within one clip */
    public record Ranked(Path frame, int index, double score) {
    }

    public List<Ranked> rank(List<Path> frames) {
        List<Ranked> ranked = new ArrayList<>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            double sharpness = sharpness(frames.get(i));
            ranked.add(new Ranked(frames.get(i), i, sharpness * positionWeight(i, frames.size())));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
        return ranked;
    }

    /** The best {@code limit} frames, back in capture order. */
    public List<Path> best(List<Path> frames, int limit) {
        if (frames.size() <= limit) {
            return frames;
        }
        return rank(frames).stream()
                .limit(limit)
                .sorted(Comparator.comparingInt(Ranked::index))
                .map(Ranked::frame)
                .toList();
    }

    public Optional<Ranked> bestFrame(List<Path> frames) {
        return rank(frames).stream().findFirst();
    }

    private static double positionWeight(int index, int total) {
        if (total <= 1) {
            return 1.0;
        }
        double position = (double) index / (total - 1);
        return EARLIEST_FRAME_WEIGHT + (1.0 - EARLIEST_FRAME_WEIGHT) * position;
    }

    /** Variance of the 3×3 Laplacian over the luminance plane. */
    double sharpness(Path image) {
        BufferedImage img = readQuietly(image);
        if (img == null || img.getWidth() < 3 || img.getHeight() < 3) {
            return 0.0;
        }

        int width = img.getWidth();
        int height = img.getHeight();

        double[] luma = new double[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                luma[y * width + x] = 0.299 * r + 0.587 * g + 0.114 * b;
            }
        }

        double sum = 0;
        double sumSquares = 0;
        int n = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int i = y * width + x;
                double laplacian = luma[i - width] + luma[i + width]
                        + luma[i - 1] + luma[i + 1]
                        - 4 * luma[i];
                sum += laplacian;
                sumSquares += laplacian * laplacian;
                n++;
            }
        }

        if (n == 0) {
            return 0.0;
        }
        double mean = sum / n;
        return Math.max(0.0, sumSquares / n - mean * mean);
    }

    private static BufferedImage readQuietly(Path image) {
        try {
            if (!Files.isRegularFile(image)) {
                return null;
            }
            return ImageIO.read(image.toFile());
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read frame {} for sharpness ranking: {}",
                    image.getFileName(), e.toString());
            return null;
        }
    }
}
