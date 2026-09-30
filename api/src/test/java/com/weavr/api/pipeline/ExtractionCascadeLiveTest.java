package com.weavr.api.pipeline;

import java.util.UUID;

import com.weavr.api.pipeline.audio.AsrTranscriber;
import com.weavr.api.pipeline.health.ExtractionAttempt;
import com.weavr.api.pipeline.health.ExtractionAttemptRecorder;
import com.weavr.api.pipeline.health.LiveClients;
import com.weavr.api.pipeline.ocr.VisualTextExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The production {@link ExtractionCascade}, unmocked through every free tier
 * — real RapidAPI (if keyed), real YouTube Data API (if keyed), real yt-dlp
 * probe and caption fetch — against one real URL. <b>Opt-in.</b>
 *
 * <p>The paid/heavy tiers (ASR via Groq, the visual/OCR tier, link and PDF
 * readers) are <em>not</em> wired here. They are not quietly mocked into
 * success either: reaching one throws, so the test fails and says which tier
 * the URL needed. A pass therefore means captions or metadata alone carried
 * the save, which is exactly the claim it's allowed to make.
 *
 * <pre>
 *   WEAVR_LIVE_CASCADE=1 WEAVR_LIVE_URL=https://www.tiktok.com/@.../video/... \
 *     ./mvnw test -Dtest=ExtractionCascadeLiveTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "WEAVR_LIVE_CASCADE", matches = "1")
class ExtractionCascadeLiveTest {

    private static final String DEFAULT_URL = "https://www.tiktok.com/@scout2015/video/6718335390845095173";

    @Test
    void extractsARealUrlThroughTheFreeTiers() {
        String url = System.getenv().getOrDefault("WEAVR_LIVE_URL", DEFAULT_URL);
        ExtractionAttemptRecorder recorder = new ExtractionAttemptRecorder();

        AsrTranscriber asr = mock(AsrTranscriber.class);
        VisualTextExtractor visual = mock(VisualTextExtractor.class);
        LinkExtractor link = mock(LinkExtractor.class);
        PdfExtractor pdf = mock(PdfExtractor.class);
        when(asr.transcribe(anyString())).thenThrow(new IllegalStateException(
                "LIVE HARNESS: cascade needed the ASR tier (captions + metadata were not enough) — not wired here"));
        when(visual.extract(anyString(), any(UUID.class))).thenThrow(new IllegalStateException(
                "LIVE HARNESS: cascade needed the visual/OCR tier — not wired here"));
        when(link.extract(anyString())).thenThrow(new IllegalStateException(
                "LIVE HARNESS: yt-dlp had no extractor, cascade fell to the link reader — not wired here"));

        ExtractionCascade cascade = new ExtractionCascade(LiveClients.ytDlp(), LiveClients.rapidYt(),
                LiveClients.youtubeData(), asr, visual, link, pdf, recorder);

        long start = System.nanoTime();
        ExtractionCascade.Extraction extraction;
        try {
            extraction = cascade.extractFromUrl(url, UUID.randomUUID());
        } finally {
            recorder.recent().forEach(ExtractionCascadeLiveTest::print);
        }
        long totalMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("LIVE url=%s source=%s provider=%s total_ms=%d text_chars=%d title=%s uploader=%s "
                        + "has_captions=%s caption_langs=%s thumbnail=%s%n",
                url, extraction.source(), extraction.detail().get("provider"), totalMs, extraction.text().length(),
                extraction.metadata().title(), extraction.metadata().uploader(), extraction.metadata().hasCaptions(),
                extraction.metadata().captionLanguages().size() + extraction.metadata().autoCaptionLanguages().size(),
                extraction.metadata().thumbnailUrl() != null);
        System.out.println("LIVE text_preview=" + extraction.text().substring(0, Math.min(300, extraction.text().length()))
                .replace('\n', ' '));

        assertThat(extraction.text()).isNotBlank();
    }

    private static void print(ExtractionAttempt a) {
        System.out.printf("LIVE attempt provider=%s category=%s latency_ms=%d http=%s ytdlp=%s detail=%s%n",
                a.provider(), a.category(), a.latencyMs(), a.httpStatus(), a.ytDlpVersion(), a.detail());
    }
}
