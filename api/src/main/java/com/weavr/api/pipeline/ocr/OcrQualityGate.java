package com.weavr.api.pipeline.ocr;

import org.springframework.stereotype.Component;

/**
 * Decides whether locally-read text is good enough to hand the cheap text model,
 * or whether this save has to spend a Flash vision request.
 *
 * <p><b>This gate is what makes "go light on OCR" safe.</b> LLM reconstruction
 * works when OCR is roughly 70–80% correct and does <em>not</em> work at 20% —
 * below that floor the model does not repair the text, it invents plausible
 * ingredients, which is worse than failing because nobody can tell. Tesseract
 * fails hard rather than gracefully on low-contrast text over gradients, so
 * "it returned something" is not evidence of anything.
 *
 * <h2>Three signals, because confidence alone is not enough</h2>
 *
 * <ul>
 *   <li><b>Mean per-word confidence</b> — tesseract's own number, and the
 *       primary signal.</li>
 *   <li><b>Usable word count</b> — four confidently-read words are still
 *       nothing to extract a recipe from.</li>
 *   <li><b>Alphabetic ratio</b> — the shape tesseract's hard failures actually
 *       take. Symbol soup ({@code |][ ~ 4/} …) is frequently read with
 *       respectable confidence, so a purely confidence-based gate passes it
 *       straight through to the model.</li>
 * </ul>
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>The plan also calls for <b>schema sanity</b> — "a recipe should yield
 * quantities and units; a product should yield a price or brand". That check
 * cannot run at this point: the knowledge type is what the Gemini call returns,
 * so this gate does not know which schema to sanity-check against, and buying
 * the type first would spend the very request the gate exists to protect. It is
 * instead applied one stage later and for free — {@link VisualTextExtractor}
 * escalates when the model itself reports it could not extract anything, which
 * is a strictly better signal than any heuristic available here.
 *
 * <p><b>Every threshold below is a guess until the eval set exists.</b> Phase 4
 * scopes thirty hand-labelled Reels measured against Flash precisely so these
 * numbers stop being guesses; both failure directions — burning the Flash pool
 * and shipping silent hallucinations — are invisible without them.
 */
@Component
public class OcrQualityGate {

    private final OcrProperties properties;

    OcrQualityGate(OcrProperties properties) {
        this.properties = properties;
    }

    public enum Verdict {
        /** Above the repair floor — send the text to the cheap model. */
        PASS,
        /** Below the floor, but there is something there — spend vision on it. */
        ESCALATE,
        /** Nothing was read at all. Vision is the only remaining option. */
        EMPTY
    }

    /**
     * @param verdict the decision
     * @param reason  a short, loggable explanation — the escalation rate has to
     *                be measurable rather than guessed, and "why" is half of that
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
     * content a recipe card carries, and a stricter test would reject the good
     * case along with the soup.
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
