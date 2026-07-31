package com.weavr.api.pipeline.ocr;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate that makes "go light on OCR" safe.
 *
 * <p>Both failure directions are silent in production — a gate that is too
 * strict burns the Flash pool, and a gate that is too loose ships invented
 * ingredients nobody reports — so the branches are worth pinning even while the
 * thresholds themselves are still guesses awaiting the eval set.
 */
class OcrQualityGateTest {

    private final OcrQualityGate gate = new OcrQualityGate(properties());

    private static OcrProperties properties() {
        return new OcrProperties(true, "tesseract", "eng", 6, Duration.ofSeconds(30),
                90, 12, 0.25, 150, null, 60.0, 8, 0.5, 2, true, 4);
    }

    private static FrameTextVoter.VotedText voted(String text, double confidence) {
        return new FrameTextVoter.VotedText(text, confidence, 3, 4, 0);
    }

    @Test
    void passesCleanTextWellAboveTheFloor() {
        OcrQualityGate.Decision decision = gate.judge(voted(
                "CREAMY TOMATO PASTA\n400g rigatoni\n2 tbsp olive oil\n1 cup heavy cream", 88));

        assertThat(decision.verdict()).isEqualTo(OcrQualityGate.Verdict.PASS);
        assertThat(decision.passed()).isTrue();
    }

    @Test
    void reportsEmptyWhenNothingWasRead() {
        OcrQualityGate.Decision decision = gate.judge(
                new FrameTextVoter.VotedText("", 0, 0, 0, 0));

        assertThat(decision.verdict()).isEqualTo(OcrQualityGate.Verdict.EMPTY);
    }

    /** Four confidently-read words are still nothing to extract a recipe from. */
    @Test
    void escalatesWhenThereIsTooLittleTextRegardlessOfConfidence() {
        OcrQualityGate.Decision decision = gate.judge(voted("FOLLOW FOR MORE", 99));

        assertThat(decision.verdict()).isEqualTo(OcrQualityGate.Verdict.ESCALATE);
        assertThat(decision.reason()).contains("words");
    }

    @Test
    void escalatesBelowTheConfidenceFloor() {
        OcrQualityGate.Decision decision = gate.judge(voted(
                "CREAMY TOMATO PASTA\n400g rigatoni\n2 tbsp olive oil\n1 cup heavy cream", 41));

        assertThat(decision.verdict()).isEqualTo(OcrQualityGate.Verdict.ESCALATE);
        assertThat(decision.reason()).contains("repair floor");
    }

    /**
     * The failure shape that a confidence-only gate lets through. Tesseract
     * reads symbol soup off low-contrast text over a gradient and is quite
     * pleased with itself about it — which is exactly how invented ingredients
     * reach a user.
     */
    @Test
    void escalatesConfidentSymbolSoup() {
        OcrQualityGate.Decision decision = gate.judge(voted(
                "|][ ~~ 4/ >> ][| ~ // \\\\ %% ^^ ][", 91));

        assertThat(decision.verdict()).isEqualTo(OcrQualityGate.Verdict.ESCALATE);
        assertThat(decision.reason()).contains("letter");
    }

    /**
     * The counter-case to the one above: a real ingredient card is full of
     * digit-led tokens, and a stricter "entirely letters" test would reject the
     * good input alongside the soup.
     */
    @Test
    void doesNotMistakeAQuantityHeavyCardForSoup() {
        OcrQualityGate.Decision decision = gate.judge(voted(
                "400g rigatoni\n2 tbsp olive oil\n1 cup heavy cream\n3 cloves garlic", 85));

        assertThat(decision.verdict()).isEqualTo(OcrQualityGate.Verdict.PASS);
    }
}
