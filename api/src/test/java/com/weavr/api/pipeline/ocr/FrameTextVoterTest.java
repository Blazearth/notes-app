package com.weavr.api.pipeline.ocr;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The consensus step — the cheapest accuracy win in the tier, and the one with
 * no analogue in a one-shot vision call.
 */
class FrameTextVoterTest {

    private final FrameTextVoter voter = new FrameTextVoter(properties(2));

    private static OcrProperties properties(int minVotes) {
        return new OcrProperties(true, "tesseract", "eng", 6, Duration.ofSeconds(30),
                90, 12, 0.25, 150, null, 60.0, 8, 0.5, minVotes, true, 4);
    }

    private static TesseractClient.FrameText frame(String text, double confidence) {
        int words = text.isBlank() ? 0 : text.split("\\s+").length;
        return new TesseractClient.FrameText(text, confidence, words);
    }

    /**
     * The whole reason this class exists: three independent reads of the same
     * burned-in overlay outvote a misread, for free.
     */
    @Test
    void repairsAMisreadByMajorityAcrossFrames() {
        FrameTextVoter.VotedText voted = voter.vote(List.of(
                frame("1 cup fl0ur", 70),
                frame("1 cup flour", 80),
                frame("1 cup flour", 85)));

        assertThat(voted.text()).isEqualTo("1 cup flour");
        assertThat(voted.framesRead()).isEqualTo(3);
    }

    /**
     * Matching on the raw string would make every misread its own line and
     * defeat voting entirely — but the emitted text has to keep the real
     * casing and punctuation, or the model loses signal it could have used.
     */
    @Test
    void matchesLinesLooselyButEmitsTheRawWinner() {
        FrameTextVoter.VotedText voted = voter.vote(List.of(
                frame("CREAMY TOMATO PASTA!", 90),
                frame("Creamy Tomato Pasta", 90),
                frame("CREAMY TOMATO PASTA!", 90)));

        assertThat(voted.text()).isEqualTo("CREAMY TOMATO PASTA!");
        assertThat(voted.linesKept()).isEqualTo(1);
    }

    /**
     * A card that repeats a line is not corroboration — it is the same frame
     * saying the same thing twice, and letting it vote twice would let one
     * frame outvote several genuinely independent ones.
     */
    @Test
    void countsALineRepeatedWithinOneFrameOnlyOnce() {
        FrameTextVoter.VotedText voted = voter.vote(List.of(
                frame("2 tbsp oil\n2 tbsp oil\n2 tbsp oil", 90),
                frame("3 cloves garlic", 90),
                frame("3 cloves garlic", 90)));

        assertThat(voted.text().lines()).containsExactly("3 cloves garlic");
        assertThat(voted.linesDropped()).isEqualTo(1);
    }

    /**
     * A two-frame clip cannot corroborate most lines. Applying the threshold
     * anyway would silently empty the result — a "no text found" that is not
     * true.
     */
    @Test
    void doesNotDemandCorroborationWhenThereAreTooFewFramesToGiveIt() {
        FrameTextVoter.VotedText voted = voter.vote(List.of(
                frame("400g rigatoni\n2 tbsp olive oil", 88)));

        assertThat(voted.text().lines()).containsExactly("400g rigatoni", "2 tbsp olive oil");
        assertThat(voted.linesDropped()).isZero();
    }

    /**
     * Enough frames to vote, but nothing agreed. Reporting "no text" would be
     * a different claim from "text I could not corroborate", and the gate — not
     * the voter — is what should judge thin evidence.
     */
    @Test
    void keepsEverythingRatherThanReturningNothingWhenNoLineIsCorroborated() {
        FrameTextVoter.VotedText voted = voter.vote(List.of(
                frame("wholly different line one", 60),
                frame("nothing alike here at all", 60),
                frame("a third unrelated string", 60)));

        assertThat(voted.linesKept()).isEqualTo(3);
        assertThat(voted.isEmpty()).isFalse();
    }

    @Test
    void weightsConfidenceByHowMuchEachFrameActuallyRead() {
        // 10 words at 90 and 1 word at 20: an unweighted mean would say 55.
        FrameTextVoter.VotedText voted = voter.vote(List.of(
                new TesseractClient.FrameText("a b c d e f g h i j", 90, 10),
                new TesseractClient.FrameText("z", 20, 1)));

        assertThat(voted.meanConfidence()).isCloseTo(83.6, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void keepsFirstAppearanceOrder() {
        FrameTextVoter.VotedText voted = voter.vote(List.of(
                frame("TITLE\ningredients\nsteps", 90),
                frame("TITLE\ningredients\nsteps", 90)));

        assertThat(voted.text().lines()).containsExactly("TITLE", "ingredients", "steps");
    }

    @Test
    void reportsEmptyWhenNoFrameYieldedAnything() {
        FrameTextVoter.VotedText voted = voter.vote(List.of(
                TesseractClient.FrameText.empty(), TesseractClient.FrameText.empty()));

        assertThat(voted.isEmpty()).isTrue();
        assertThat(voted.framesRead()).isZero();
        assertThat(voted.meanConfidence()).isZero();
    }

    /** Digits distinguish real lines; case and stray punctuation do not. */
    @Test
    void normalisationKeepsWhatDistinguishesLinesAndDropsWhatDoesNot() {
        assertThat(FrameTextVoter.normalise("CREAMY, Tomato — Pasta!"))
                .isEqualTo(FrameTextVoter.normalise("creamy tomato pasta"));
        assertThat(FrameTextVoter.normalise("1 cup"))
                .isNotEqualTo(FrameTextVoter.normalise("7 cup"));
    }
}
