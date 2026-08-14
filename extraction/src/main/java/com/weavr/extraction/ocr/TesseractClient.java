package com.weavr.extraction.ocr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.weavr.extraction.process.ExternalProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads text off one frame with tesseract, as an external process. Ported
 * unchanged from {@code api}'s {@code TesseractClient}.
 *
 * <p>The output format is {@code tsv}, not plain text: the escalation gate
 * needs tesseract's per-word confidence, and plain-text output throws that
 * away.
 */
@Component
public class TesseractClient {

    private static final Logger log = LoggerFactory.getLogger(TesseractClient.class);

    /** TSV column indices, per tesseract's own header row. */
    private static final int COL_LEVEL = 0;
    private static final int COL_BLOCK = 2;
    private static final int COL_PARAGRAPH = 3;
    private static final int COL_LINE = 4;
    private static final int COL_CONF = 10;
    private static final int COL_TEXT = 11;

    /** A word row; every other level is layout structure with no text. */
    private static final int LEVEL_WORD = 5;

    private final ExternalProcess processes;
    private final OcrProperties properties;

    TesseractClient(ExternalProcess processes, OcrProperties properties) {
        this.processes = processes;
        this.properties = properties;
    }

    /**
     * @param text           the recognised text, newline-separated by source line
     * @param meanConfidence mean per-word confidence, 0–100, or 0 when nothing was read
     * @param wordCount      how many words that mean is over
     */
    public record FrameText(String text, double meanConfidence, int wordCount) {

        static FrameText empty() {
            return new FrameText("", 0.0, 0);
        }

        public boolean isEmpty() {
            return wordCount == 0;
        }
    }

    public FrameText read(Path image) {
        ExternalProcess.Result result = processes.run(List.of(
                properties.binary(),
                image.toString(),
                // "stdout" is tesseract's own sentinel for "don't write a file".
                "stdout",
                "-l", properties.language(),
                "--psm", String.valueOf(properties.pageSegmentation()),
                "tsv"), properties.timeout());

        if (!result.succeeded()) {
            // One unreadable frame is not a failed extraction — the other
            // frames may carry the same burned-in overlay.
            log.debug("tesseract could not read {}: {}", image.getFileName(),
                    result.timedOut() ? "timed out" : result.stderr());
            return FrameText.empty();
        }

        return parseTsv(result.stdout());
    }

    /**
     * Parses tesseract's TSV. Only {@code level = 5} rows carry words; the
     * rest describe page, block, paragraph and line boxes and have
     * {@code conf = -1} with empty text.
     */
    static FrameText parseTsv(String tsv) {
        if (tsv == null || tsv.isBlank()) {
            return FrameText.empty();
        }

        StringBuilder text = new StringBuilder();
        String currentLine = null;
        double confidenceSum = 0;
        int words = 0;

        for (String row : tsv.split("\\R")) {
            String[] cols = row.split("\t", -1);
            if (cols.length <= COL_TEXT) {
                continue;
            }
            if (!String.valueOf(LEVEL_WORD).equals(cols[COL_LEVEL].strip())) {
                continue;
            }

            String word = cols[COL_TEXT].strip();
            if (word.isEmpty()) {
                continue;
            }

            double confidence;
            try {
                confidence = Double.parseDouble(cols[COL_CONF].strip());
            } catch (NumberFormatException e) {
                continue;
            }
            // -1 marks a box tesseract emitted without recognising anything.
            if (confidence < 0) {
                continue;
            }

            String lineKey = cols[COL_BLOCK] + "/" + cols[COL_PARAGRAPH] + "/" + cols[COL_LINE];
            if (currentLine == null) {
                currentLine = lineKey;
            } else if (!currentLine.equals(lineKey)) {
                text.append('\n');
                currentLine = lineKey;
            } else {
                text.append(' ');
            }

            text.append(word);
            confidenceSum += confidence;
            words++;
        }

        if (words == 0) {
            return FrameText.empty();
        }
        return new FrameText(text.toString(), confidenceSum / words, words);
    }

    /** Builds the list of readings, tolerating frames tesseract could not read. */
    public List<FrameText> readAll(List<Path> images) {
        List<FrameText> readings = new ArrayList<>(images.size());
        for (Path image : images) {
            readings.add(read(image));
        }
        return readings;
    }
}
