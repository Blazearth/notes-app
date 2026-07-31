package com.weavr.api.pipeline.ocr;

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
 * Ranks frames by sharpness, locally, with no model call (CA#15).
 *
 * <p><b>Never spend a request choosing a picture.</b> Variance of the Laplacian
 * is the standard cheap focus measure: the Laplacian responds to intensity
 * changes, so a blurred frame's response clusters near zero and a crisp one
 * spreads out. Text overlays are all edges, which makes this unusually
 * well-behaved on exactly the frames this tier produces.
 *
 * <p>Two jobs today, both real:
 *
 * <ul>
 *   <li><b>Picking which frames a vision escalation carries.</b> The escalation
 *       is capped at a handful of images, and a motion-blurred frame costs
 *       tokens and returns nothing — so rank, then take the top few.</li>
 *   <li><b>Nominating a thumbnail</b> when the platform metadata carries none.
 *       Recorded in the stage payload; actually storing the image needs a
 *       Supabase Storage path that does not exist yet.</li>
 * </ul>
 *
 * <p><b>End-weighted</b>, per the teardown: the opening frame of a Reel is
 * disproportionately a title card, a logo, or a face mid-blink, while the
 * payoff shot lands later. The weight is gentle — it breaks ties toward the
 * back half rather than overriding a genuinely sharper early frame.
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

    /**
     * @param frames in capture order — the order is the position information,
     *               so a shuffled list silently mis-weights
     */
    public List<Ranked> rank(List<Path> frames) {
        List<Ranked> ranked = new ArrayList<>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            double sharpness = sharpness(frames.get(i));
            ranked.add(new Ranked(frames.get(i), i, sharpness * positionWeight(i, frames.size())));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
        return ranked;
    }

    /** The best {@code limit} frames, back in capture order — a vision call reads them as a sequence. */
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

    /**
     * Variance of the 3×3 Laplacian over the luminance plane.
     *
     * <p>Reads at most one image into memory at a time, and returns 0 rather
     * than throwing on an unreadable frame — a truncated final JPEG is a normal
     * outcome of a bounded ffmpeg run, and it should rank last, not fail a save.
     */
    double sharpness(Path image) {
        BufferedImage img = readQuietly(image);
        if (img == null || img.getWidth() < 3 || img.getHeight() < 3) {
            return 0.0;
        }

        int width = img.getWidth();
        int height = img.getHeight();

        // Precompute luminance once; the kernel reads each pixel up to 5 times
        // and getRGB per access is the whole cost of this method.
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

        // Welford would be tidier, but the two-pass sum is exact enough here and
        // the value ranges are small.
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
