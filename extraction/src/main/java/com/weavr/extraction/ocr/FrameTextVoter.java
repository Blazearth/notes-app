package com.weavr.extraction.ocr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Merges several frames' OCR output into one text blob by voting. Ported
 * unchanged from {@code api}'s {@code FrameTextVoter}.
 *
 * <p>A burned-in overlay persists across many frames, so tesseract reads the
 * same string repeatedly and its errors are mostly independent between reads —
 * a majority vote across frames corrects them for free, with no analogue in a
 * one-shot vision call.
 */
@Component
public class FrameTextVoter {

    private final OcrProperties properties;

    FrameTextVoter(OcrProperties properties) {
        this.properties = properties;
    }

    /**
     * @param text            the merged blob, one voted line per line
     * @param meanConfidence  word-count-weighted mean across contributing frames
     * @param framesRead      how many frames yielded any text at all
     * @param linesKept       lines that survived the vote
     * @param linesDropped    lines seen too few times to trust
     */
    public record VotedText(String text, double meanConfidence, int framesRead,
                            int linesKept, int linesDropped) {

        public boolean isEmpty() {
            return linesKept == 0;
        }
    }

    public VotedText vote(List<TesseractClient.FrameText> readings) {
        // key -> variants of the raw line and how often each was seen
        Map<String, Map<String, Integer>> votes = new LinkedHashMap<>();

        int framesRead = 0;
        double weightedConfidence = 0;
        int totalWords = 0;

        for (TesseractClient.FrameText reading : readings) {
            if (reading.isEmpty()) {
                continue;
            }
            framesRead++;
            weightedConfidence += reading.meanConfidence() * reading.wordCount();
            totalWords += reading.wordCount();

            // A line repeated *within* one frame is one sighting, not two.
            List<String> seenInThisFrame = new ArrayList<>();
            for (String raw : reading.text().split("\\R")) {
                String line = raw.strip();
                if (line.isEmpty()) {
                    continue;
                }
                String key = normalise(line);
                if (key.isEmpty() || seenInThisFrame.contains(key)) {
                    continue;
                }
                seenInThisFrame.add(key);
                votes.computeIfAbsent(key, k -> new LinkedHashMap<>())
                        .merge(line, 1, Integer::sum);
            }
        }

        if (votes.isEmpty()) {
            return new VotedText("", 0.0, framesRead, 0, 0);
        }

        // Only demand corroboration when there was something to corroborate
        // against.
        int required = framesRead >= properties.minFrameVotes() ? properties.minFrameVotes() : 1;

        List<String> kept = new ArrayList<>();
        int dropped = 0;
        for (Map<String, Integer> variants : votes.values()) {
            int sightings = variants.values().stream().mapToInt(Integer::intValue).sum();
            if (sightings < required) {
                dropped++;
                continue;
            }
            kept.add(mostCommon(variants));
        }

        // Everything was thinly evidenced. Falling through with nothing would
        // report "OCR found no text" when it actually found text it merely
        // could not corroborate.
        if (kept.isEmpty()) {
            for (Map<String, Integer> variants : votes.values()) {
                kept.add(mostCommon(variants));
            }
            dropped = 0;
        }

        double mean = totalWords == 0 ? 0.0 : weightedConfidence / totalWords;
        return new VotedText(String.join("\n", kept), mean, framesRead, kept.size(), dropped);
    }

    /** The most-seen spelling, ties broken by first appearance. */
    private static String mostCommon(Map<String, Integer> variants) {
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> variant : variants.entrySet()) {
            if (variant.getValue() > bestCount) {
                best = variant.getKey();
                bestCount = variant.getValue();
            }
        }
        return best;
    }

    /**
     * The vote key: case, spacing and edge punctuation are exactly the
     * differences between two reads of the same overlay that should not split
     * the vote. Interior punctuation and digits are kept.
     */
    static String normalise(String line) {
        return line.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s./%-]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
