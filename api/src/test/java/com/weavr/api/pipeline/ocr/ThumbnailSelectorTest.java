package com.weavr.api.pipeline.ocr;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sharpness ranking, on real image bytes rather than a mocked scorer — the
 * arithmetic is the component, so mocking it would test nothing.
 */
class ThumbnailSelectorTest {

    private final ThumbnailSelector selector = new ThumbnailSelector();

    /** Hard edges everywhere: what a text overlay looks like to a Laplacian. */
    private static Path sharp(Path dir, String name) throws IOException {
        BufferedImage img = new BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 120, 120);
        g.setColor(Color.WHITE);
        for (int y = 0; y < 120; y += 12) {
            g.fillRect(0, y, 120, 6);
        }
        g.dispose();
        return write(dir, name, img);
    }

    /** A smooth gradient: no high-frequency content, so near-zero variance. */
    private static Path blurred(Path dir, String name) throws IOException {
        BufferedImage img = new BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 120; y++) {
            for (int x = 0; x < 120; x++) {
                int v = (x + y) % 256;
                img.setRGB(x, y, new Color(v, v, v).getRGB());
            }
        }
        return write(dir, name, img);
    }

    private static Path write(Path dir, String name, BufferedImage img) throws IOException {
        Path file = dir.resolve(name);
        ImageIO.write(img, "png", file.toFile());
        return file;
    }

    @Test
    void scoresACrispFrameFarAboveASmoothOne(@TempDir Path dir) throws IOException {
        assertThat(selector.sharpness(sharp(dir, "a.png")))
                .isGreaterThan(selector.sharpness(blurred(dir, "b.png")) * 10);
    }

    @Test
    void ranksTheSharpestFrameFirst(@TempDir Path dir) throws IOException {
        List<Path> frames = List.of(
                blurred(dir, "1.png"), sharp(dir, "2.png"), blurred(dir, "3.png"));

        assertThat(selector.bestFrame(frames)).isPresent()
                .get().extracting(ThumbnailSelector.Ranked::index).isEqualTo(1);
    }

    /**
     * End-weighting, per the teardown: a Reel's opening frame is
     * disproportionately a title card or a logo, and the payoff lands later.
     * Two equally sharp frames should therefore not tie.
     */
    @Test
    void breaksTiesTowardTheEndOfTheClip(@TempDir Path dir) throws IOException {
        List<Path> frames = List.of(sharp(dir, "1.png"), sharp(dir, "2.png"));

        assertThat(selector.bestFrame(frames)).isPresent()
                .get().extracting(ThumbnailSelector.Ranked::index).isEqualTo(1);
    }

    /**
     * ...but gently. A blurry last frame must not beat a sharp first one, or
     * the weighting has stopped being a tiebreak and become the ranking.
     */
    @Test
    void doesNotLetPositionOverrideRealSharpness(@TempDir Path dir) throws IOException {
        List<Path> frames = List.of(sharp(dir, "1.png"), blurred(dir, "2.png"));

        assertThat(selector.bestFrame(frames)).isPresent()
                .get().extracting(ThumbnailSelector.Ranked::index).isEqualTo(0);
    }

    /** A vision call reads the frames as a sequence, so selection must not reorder them. */
    @Test
    void returnsChosenFramesBackInCaptureOrder(@TempDir Path dir) throws IOException {
        List<Path> frames = List.of(
                blurred(dir, "1.png"), sharp(dir, "2.png"),
                blurred(dir, "3.png"), sharp(dir, "4.png"));

        assertThat(selector.best(frames, 2))
                .containsExactly(frames.get(1), frames.get(3));
    }

    @Test
    void returnsEverythingWhenThereAreFewerFramesThanTheLimit(@TempDir Path dir) throws IOException {
        List<Path> frames = List.of(sharp(dir, "1.png"));

        assertThat(selector.best(frames, 4)).isEqualTo(frames);
    }

    /**
     * A truncated final JPEG is a normal outcome of a bounded ffmpeg run. It
     * should rank last, not fail the save.
     */
    @Test
    void ranksAnUnreadableFrameLastInsteadOfThrowing(@TempDir Path dir) throws IOException {
        Path corrupt = dir.resolve("corrupt.jpg");
        Files.writeString(corrupt, "this is not a JPEG");

        List<Path> frames = List.of(corrupt, sharp(dir, "ok.png"), dir.resolve("missing.png"));

        assertThat(selector.sharpness(corrupt)).isZero();
        assertThat(selector.sharpness(dir.resolve("missing.png"))).isZero();
        assertThat(selector.bestFrame(frames)).isPresent()
                .get().extracting(ThumbnailSelector.Ranked::index).isEqualTo(1);
    }
}
