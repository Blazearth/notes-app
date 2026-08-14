package com.weavr.extraction.ocr;

import org.springframework.stereotype.Component;

/**
 * Decides whether locally-read text is good enough to hand the cheap text
 * model, or whether the caller should spend a vision request. Ported
 * unchanged from {@code api}'s {@code OcrQualityGate}. Unlike the monolith's
 * copy, nothing on this side of the process boundary ever spends the vision
 * request itself — a failed gate here means "return frame artifacts", not
 * "call Gemini" (docs/extraction-architecture.md Part D: "the OCR quality
 * gate stays in the service... but vision escalation does not").
 *
 * <h2>Three signals, because confidence alone is not enough</h2>
 * <ul>
 *   <li><b>Mean per-word confidence</b> — tesseract's own number, and the primary signal.</li>
 *   <li><b>Usable word count</b> — four confidently-read words are still nothing to extract from.</li>
 *   <li><b>Alphabetic ratio</b> — the shape tesseract's hard failures actually
 *       take. Symbol soup is frequently read with respectable confidence, so a
 *       purely confidence-based gate passes it straight through.</li>
 * </ul>
 */
@Component
public class OcrQualityGate {

    private final OcrProperties properties;

    OcrQualityGate(OcrProperties properties) {
        this.properties = properties;
    }

    public enum Verdict {
        /** Above the repair floor — send the text onward as the result. */
        PASS,
        /** Below the floor, but there is something there — offer it as an artifact-backed escalation candidate. */
        ESCALATE,
        /** Nothing was read at all. */
        EMPTY
    }

    /**
     * @param verdict the decision
     * @param reason  a short, loggable explanation
     */
    public record Decision(Verdict verdict, String reason) {

        public boolean passed() {
            return verdict == Verdict.PASS;
        }
    }

    public Decision judge(FrameTextVoter.VotedText voted) {
        if (voted.isEmpty() || voted.text().isBlank()) {
            return new Decision(Verdict.EMPTY, "no text read from any frame");
        }

        String[] tokens = voted.text().split("\\s+");
        int words = tokens.length;
        if (words < properties.minWords()) {
            return new Decision(Verdict.ESCALATE,
                    "only %d words read, floor is %d".formatted(words, properties.minWords()));
        }

        double alphaRatio = alphabeticRatio(tokens);
        if (alphaRatio < properties.minAlphaRatio()) {
            return new Decision(Verdict.ESCALATE,
                    "only %.0f%% of tokens contain a letter, floor is %.0f%%"
                            .formatted(alphaRatio * 100, properties.minAlphaRatio() * 100));
        }

        if (voted.meanConfidence() < properties.minMeanConfidence()) {
            return new Decision(Verdict.ESCALATE,
                    "mean confidence %.1f is below the repair floor of %.1f"
                            .formatted(voted.meanConfidence(), properties.minMeanConfidence()));
        }

        return new Decision(Verdict.PASS,
                "%d words at mean confidence %.1f across %d frames"
                        .formatted(words, voted.meanConfidence(), voted.framesRead()));
    }

    /**
     * A token counts as alphabetic if it contains any letter — not if it is
     * <em>entirely</em> letters. {@code 400g} and {@code 1/2} are exactly the
     * content a recipe card carries.
     */
    private static double alphabeticRatio(String[] tokens) {
        int withLetters = 0;
        int counted = 0;
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            counted++;
            if (token.codePoints().anyMatch(Character::isLetter)) {
                withLetters++;
            }
        }
        return counted == 0 ? 0.0 : (double) withLetters / counted;
    }
}
