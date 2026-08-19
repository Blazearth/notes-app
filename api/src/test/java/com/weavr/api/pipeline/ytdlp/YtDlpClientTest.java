package com.weavr.api.pipeline.ytdlp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.weavr.api.pipeline.ExternalProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Caption selection and partial-failure handling, both of which were wrong until
 * a real yt-dlp was run against a real video.
 *
 * <p>The fixtures below are trimmed copies of genuine output for one TED talk:
 * an uploaded English track and YouTube's machine-generated {@code en-orig}
 * track for the same video.
 */
class YtDlpClientTest {

    /** An uploaded subtitle track: no karaoke timings, one cue per utterance. */
    private static final String UPLOADED_VTT = """
            WEBVTT
            Kind: captions
            Language: en

            00:00:19.514 --> 00:00:21.226
            Hear that?

            00:00:21.762 --> 00:00:24.027
            That's nothing.

            00:00:24.736 --> 00:00:28.381
            Which is what I, as a speaker
            at today's conference,

            00:00:28.381 --> 00:00:29.816
            have for you all.

            00:00:30.216 --> 00:00:33.100
            I have nothing. Nada. Zip. Zilch.
            """;

    /**
     * The same speech as auto-captions: a rolling window that restates the
     * previous line, plus a per-word inline timing tag. Several times the bytes
     * for less of the content — which is exactly why ranking on file size picks
     * the wrong one.
     */
    private static final String AUTO_VTT = """
            WEBVTT
            Kind: captions
            Language: en

            00:00:19.560 --> 00:00:21.950 align:start position:0%
            \s
            Hear<00:00:19.720><c> that?</c>

            00:00:21.950 --> 00:00:21.960 align:start position:0%
            Hear that?
            \s

            00:00:21.960 --> 00:00:24.830 align:start position:0%
            Hear that?
            That's<00:00:22.960><c> nothing.</c>

            00:00:24.830 --> 00:00:24.840 align:start position:0%
            That's nothing.
            \s

            00:00:24.840 --> 00:00:28.400 align:start position:0%
            That's nothing.
            Which<00:00:25.100><c> is</c><00:00:25.300><c> what</c><00:00:25.500><c> I,</c><00:00:25.700><c> as</c><00:00:25.900><c> a</c><00:00:26.100><c> speaker</c>
            """;

    private final ExternalProcess processes = mock(ExternalProcess.class);
    private final YtDlpClient client =
            new YtDlpClient(processes, new ObjectMapper(), properties());

    private static YtDlpProperties properties() {
        return new YtDlpProperties("yt-dlp", Duration.ofSeconds(60), Duration.ofSeconds(90),
                Duration.ofSeconds(120), Duration.ofSeconds(180), null, null, null, null);
    }

    private static ExternalProcess.Result ok() {
        return new ExternalProcess.Result(0, "", "", false, false);
    }

    private static ExternalProcess.Result failed(String stderr) {
        return new ExternalProcess.Result(1, "", stderr, false, false);
    }

    /** Makes the mocked run write the given files into the working directory. */
    private void runWrites(ExternalProcess.Result result, String... nameThenBody) {
        doAnswer(invocation -> {
            Path workDir = invocation.getArgument(2);
            for (int i = 0; i < nameThenBody.length; i += 2) {
                Files.writeString(workDir.resolve(nameThenBody[i]), nameThenBody[i + 1],
                        StandardCharsets.UTF_8);
            }
            return result;
        }).when(processes).run(anyList(), any(Duration.class), any(Path.class));
    }

    /**
     * The defect this exists to prevent: the auto track is the larger file and
     * the poorer transcript, so "largest wins" chose it every time.
     */
    @Test
    void prefersTheTrackWithMoreProseNotTheLargerFile(@TempDir Path workDir) {
        assertThat(AUTO_VTT.getBytes(StandardCharsets.UTF_8).length)
                .as("fixture must reproduce the real inversion: auto is the bigger file")
                .isGreaterThan(UPLOADED_VTT.getBytes(StandardCharsets.UTF_8).length);

        runWrites(ok(), "v.en.vtt", UPLOADED_VTT, "v.en-orig.vtt", AUTO_VTT);

        Optional<String> captions = client.fetchCaptions("https://example.com/v", workDir);

        assertThat(captions).isPresent();
        assertThat(captions.get())
                .contains("I have nothing. Nada. Zip. Zilch.")
                .contains("have for you all");
    }

    /**
     * yt-dlp exits non-zero when <em>any</em> requested track fails, even after
     * writing a complete one. Throwing on the exit code alone discarded a
     * transcript we had already paid for — observed as a real 429 on the second
     * language of a multi-track fetch.
     */
    @Test
    void keepsCaptionsThatLandedBeforeANonZeroExit(@TempDir Path workDir) {
        runWrites(failed("ERROR: Unable to download video subtitles for 'en-orig': HTTP Error 429: Too Many Requests"),
                "v.en.vtt", UPLOADED_VTT);

        Optional<String> captions = client.fetchCaptions("https://example.com/v", workDir);

        assertThat(captions).isPresent();
        assertThat(captions.get()).contains("Hear that?");
    }

    /** Nothing salvageable: the failure has to surface so the job can classify it. */
    @Test
    void throwsWhenTheRunFailedAndWroteNothing(@TempDir Path workDir) {
        when(processes.run(anyList(), any(Duration.class), any(Path.class)))
                .thenReturn(failed("ERROR: HTTP Error 429: Too Many Requests"));

        assertThatThrownBy(() -> client.fetchCaptions("https://example.com/v", workDir))
                .isInstanceOf(YtDlpFailedException.class);
    }

    /**
     * The observed shape of "no English track exists": yt-dlp prints "There are
     * no subtitles for the requested languages" and exits 0. Not an error — the
     * cascade falls through to metadata.
     */
    @Test
    void returnsEmptyWhenTheRunSucceededButWroteNothing(@TempDir Path workDir) {
        when(processes.run(anyList(), any(Duration.class), any(Path.class)))
                .thenReturn(ok());

        assertThat(client.fetchCaptions("https://example.com/v", workDir)).isEmpty();
    }

    /** A .vtt containing only headers is not usable text. */
    @Test
    void ignoresAVttWithNoCues(@TempDir Path workDir) {
        runWrites(ok(), "v.en.vtt", "WEBVTT\nKind: captions\nLanguage: en\n");

        assertThat(client.fetchCaptions("https://example.com/v", workDir)).isEmpty();
    }

    /**
     * Guards the pattern that caused the 429 in the first place: `en.*` is a
     * regex, and yt-dlp expands it across every synthesised
     * {@code <source>-<target>} translation track.
     */
    @Test
    void asksForExactLanguageCodesRatherThanARegex() {
        assertThat(properties().subtitleLangs())
                .isEqualTo("en,en-orig")
                .doesNotContain("*");
    }

    /**
     * Field names and nesting taken from a real {@code --dump-single-json}.
     * {@code subtitles} holds uploaded tracks, {@code automatic_captions} holds
     * machine ones — and on YouTube the latter routinely lists 150+ languages,
     * so {@code hasCaptions()} is almost always true there.
     */
    @Test
    void probeReadsTheFieldsRealYtDlpEmits() {
        String json = """
                {
                  "id": "8S0FDjFBj8o",
                  "title": "The magic of not giving a...",
                  "description": "Filmed at TEDxVienna.",
                  "uploader": "TEDx Talks",
                  "duration": 704,
                  "thumbnail": "https://i.ytimg.com/vi/8S0FDjFBj8o/maxresdefault.jpg",
                  "subtitles": { "en": [{ "ext": "vtt" }] },
                  "automatic_captions": { "en": [{ "ext": "vtt" }], "en-orig": [{ "ext": "vtt" }], "ar": [] }
                }
                """;
        when(processes.run(anyList(), any(Duration.class))).thenReturn(
                new ExternalProcess.Result(0, json, "", false, false));

        SourceMetadata metadata = client.probe("https://example.com/v");

        assertThat(metadata.id()).isEqualTo("8S0FDjFBj8o");
        assertThat(metadata.uploader()).isEqualTo("TEDx Talks");
        assertThat(metadata.durationSeconds()).isEqualTo(704.0);
        assertThat(metadata.captionLanguages()).containsExactly("en");
        assertThat(metadata.autoCaptionLanguages()).containsExactlyInAnyOrder("en", "en-orig", "ar");
        assertThat(metadata.hasCaptions()).isTrue();
        assertThat(metadata.asText()).contains("TEDxVienna");
    }

    /**
     * Field name and array shape taken from a real {@code --write-comments}
     * dump: {@code is_pinned: true} on exactly the pinned comment, others
     * false or absent. Confirmed against a real video whose pinned comment
     * carried the full recipe the description only summarised.
     */
    @Test
    void probeExtractsThePinnedComment() {
        String json = """
                {
                  "id": "x",
                  "subtitles": {},
                  "automatic_captions": {},
                  "comments": [
                    { "text": "first!", "is_pinned": false },
                    { "text": "1½ cups maida, bake at 180C for 15-20 minutes", "is_pinned": true },
                    { "text": "nice recipe", "is_pinned": false }
                  ]
                }
                """;
        when(processes.run(anyList(), any(Duration.class))).thenReturn(
                new ExternalProcess.Result(0, json, "", false, false));

        SourceMetadata metadata = client.probe("https://example.com/v");

        assertThat(metadata.pinnedComment()).isEqualTo("1½ cups maida, bake at 180C for 15-20 minutes");
        assertThat(metadata.asText()).contains("Pinned comment:", "180C for 15-20 minutes");
    }

    /** The best-effort supplement (used by ExtractionCascade when RapidAPI already succeeded) returns the same comment. */
    @Test
    void fetchPinnedCommentReturnsTheSameCommentProbeWould() {
        String json = """
                {
                  "id": "x",
                  "subtitles": {},
                  "automatic_captions": {},
                  "comments": [
                    { "text": "1½ cups maida, bake at 180C for 15-20 minutes", "is_pinned": true }
                  ]
                }
                """;
        when(processes.run(anyList(), any(Duration.class))).thenReturn(
                new ExternalProcess.Result(0, json, "", false, false));

        assertThat(client.fetchPinnedComment("https://example.com/v"))
                .contains("1½ cups maida, bake at 180C for 15-20 minutes");
    }

    /** Unlike every other method here, a yt-dlp failure must not escape — it's the common case on Render. */
    @Test
    void fetchPinnedCommentSwallowsAYtDlpFailureAndReturnsEmpty() {
        when(processes.run(anyList(), any(Duration.class))).thenReturn(
                new ExternalProcess.Result(1, "", "ERROR: Sign in to confirm you're not a bot", false, false));

        assertThat(client.fetchPinnedComment("https://example.com/v")).isEmpty();
    }

    /** A malformed response is swallowed the same way a process failure is — never thrown. */
    @Test
    void fetchPinnedCommentSwallowsAMalformedResponseAndReturnsEmpty() {
        when(processes.run(anyList(), any(Duration.class))).thenReturn(
                new ExternalProcess.Result(0, "not json", "", false, false));

        assertThat(client.fetchPinnedComment("https://example.com/v")).isEmpty();
    }

    @Test
    void fetchPinnedCommentReturnsEmptyWhenNoneIsPinned() {
        when(processes.run(anyList(), any(Duration.class))).thenReturn(new ExternalProcess.Result(
                0, "{\"id\":\"x\",\"subtitles\":{},\"automatic_captions\":{},\"comments\":[{\"text\":\"hi\",\"is_pinned\":false}]}",
                "", false, false));

        assertThat(client.fetchPinnedComment("https://example.com/v")).isEmpty();
    }

    /** No comment is pinned, comments are off, or the field is absent entirely — all the same to the caller. */
    @Test
    void probeReturnsNullPinnedCommentWhenNoneIsPinned() {
        when(processes.run(anyList(), any(Duration.class))).thenReturn(new ExternalProcess.Result(
                0, "{\"id\":\"x\",\"subtitles\":{},\"automatic_captions\":{},\"comments\":[{\"text\":\"hi\",\"is_pinned\":false}]}",
                "", false, false));

        assertThat(client.probe("https://example.com/v").pinnedComment()).isNull();
    }

    /**
     * Instagram never sets {@code is_pinned} at all — confirmed against a
     * real Reel's real {@code --write-comments} dump: 14 comments, all with
     * the field simply absent. The uploader's own comment is the fallback,
     * matched on {@code author_id == uploader_id}, a fact already in the
     * response rather than a guess.
     */
    @Test
    void fallsBackToTheUploaderSOwnCommentWhenNothingIsFlaggedPinned() {
        String json = """
                {
                  "id": "x",
                  "uploader_id": "creator123",
                  "subtitles": {},
                  "automatic_captions": {},
                  "comments": [
                    { "text": "nice video!", "author_id": "randomfan1" },
                    { "text": "Recipe: 2 cups flour, 1 tsp salt, bake 20 min", "author_id": "creator123" },
                    { "text": "love this", "author_id": "randomfan2" }
                  ]
                }
                """;
        when(processes.run(anyList(), any(Duration.class))).thenReturn(
                new ExternalProcess.Result(0, json, "", false, false));

        SourceMetadata metadata = client.probe("https://example.com/v");

        assertThat(metadata.pinnedComment()).isEqualTo("Recipe: 2 cups flour, 1 tsp salt, bake 20 min");
    }

    /** A YouTube-style {@code is_pinned} flag still wins even when an uploader-authored comment also exists. */
    @Test
    void preferstisPinnedOverUploaderMatchWhenBothArePresent() {
        String json = """
                {
                  "id": "x",
                  "uploader_id": "creator123",
                  "subtitles": {},
                  "automatic_captions": {},
                  "comments": [
                    { "text": "just a reply from me", "author_id": "creator123", "is_pinned": false },
                    { "text": "the actual pinned recipe", "author_id": "randomfan1", "is_pinned": true }
                  ]
                }
                """;
        when(processes.run(anyList(), any(Duration.class))).thenReturn(
                new ExternalProcess.Result(0, json, "", false, false));

        assertThat(client.probe("https://example.com/v").pinnedComment())
                .isEqualTo("the actual pinned recipe");
    }

    /** The probe call is one process invocation, not two — comments piggyback on the same dump. YouTube only. */
    @Test
    void probeFetchesCommentsForYouTubeInTheSameCallBoundedToTwenty() {
        when(processes.run(anyList(), any(Duration.class))).thenReturn(new ExternalProcess.Result(
                0, "{\"id\":\"x\",\"subtitles\":{},\"automatic_captions\":{}}", "", false, false));

        client.probe("https://www.youtube.com/watch?v=dQw4w9WgXcQ");

        org.mockito.ArgumentCaptor<List<String>> command =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(processes).run(command.capture(), any(Duration.class));
        assertThat(command.getValue())
                .contains("--write-comments", "--extractor-args", "youtube:max_comments=20,20,0,0");
    }

    /**
     * Instagram (and every non-YouTube source) must NOT get {@code --write-comments}.
     * The Instagram extractor fires paginated comment requests for each Reel when
     * the flag is present, multiplying the per-Reel request count 3–4×. Render's
     * datacenter IP sits on Instagram's rate-limit block list; the multiplied
     * request count tips every window into a 429 and the retry extends the ban.
     */
    @Test
    void probeDoesNotFetchCommentsForInstagram() {
        when(processes.run(anyList(), any(Duration.class))).thenReturn(new ExternalProcess.Result(
                0, "{\"id\":\"x\",\"subtitles\":{},\"automatic_captions\":{}}", "", false, false));

        client.probe("https://www.instagram.com/reel/DcEbWBdRGk5/");

        org.mockito.ArgumentCaptor<List<String>> command =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(processes).run(command.capture(), any(Duration.class));
        assertThat(command.getValue())
                .doesNotContain("--write-comments")
                .doesNotContain("youtube:max_comments=20,20,0,0");
    }

    /** Covers the URL shapes isYoutubeUrl must recognise (and must NOT recognise). */
    @Test
    void isYoutubeUrlRecognisesYouTubeShapesOnly() {
        assertThat(YtDlpClient.isYoutubeUrl("https://www.youtube.com/watch?v=abc")).isTrue();
        assertThat(YtDlpClient.isYoutubeUrl("https://youtu.be/abc")).isTrue();
        assertThat(YtDlpClient.isYoutubeUrl("https://youtube.com/shorts/abc")).isTrue();
        assertThat(YtDlpClient.isYoutubeUrl("https://music.youtube.com/watch?v=abc")).isTrue();
        assertThat(YtDlpClient.isYoutubeUrl("https://m.youtube.com/watch?v=abc")).isTrue();
        assertThat(YtDlpClient.isYoutubeUrl("https://YOUTUBE.COM/watch?v=abc")).isTrue(); // case-insensitive

        assertThat(YtDlpClient.isYoutubeUrl("https://www.instagram.com/reel/abc/")).isFalse();
        assertThat(YtDlpClient.isYoutubeUrl("https://www.tiktok.com/@user/video/1")).isFalse();
        assertThat(YtDlpClient.isYoutubeUrl("https://example.com/v")).isFalse();
        assertThat(YtDlpClient.isYoutubeUrl(null)).isFalse();
    }

    /** A post with neither kind of track: the cascade must not attempt a fetch. */
    @Test
    void probeReportsNoCaptionsWhenBothMapsAreEmpty() {
        when(processes.run(anyList(), any(Duration.class))).thenReturn(new ExternalProcess.Result(
                0, "{\"id\":\"x\",\"title\":\"Reel\",\"subtitles\":{},\"automatic_captions\":{}}",
                "", false, false));

        assertThat(client.probe("https://example.com/v").hasCaptions()).isFalse();
    }

    /** The download itself: audio only, bounded, and the extension isn't known ahead of time. */
    @Test
    void downloadAudioReturnsWhicheverFileLandedInTheWorkDir(@TempDir Path workDir) {
        runWrites(ok(), "v.m4a", "not real audio, just bytes to find");

        Optional<Path> audio = client.downloadAudio("https://example.com/v", workDir, 90);

        assertThat(audio).isPresent();
        assertThat(audio.get().getFileName().toString()).isEqualTo("v.m4a");
    }

    /** Succeeded but wrote nothing: this source has no audio track. Not an error. */
    @Test
    void downloadAudioReturnsEmptyWhenTheRunSucceededButWroteNothing(@TempDir Path workDir) {
        when(processes.run(anyList(), any(Duration.class), any(Path.class))).thenReturn(ok());

        assertThat(client.downloadAudio("https://example.com/v", workDir, 90)).isEmpty();
    }

    @Test
    void downloadAudioThrowsWhenTheRunFailedAndWroteNothing(@TempDir Path workDir) {
        when(processes.run(anyList(), any(Duration.class), any(Path.class)))
                .thenReturn(failed("ERROR: This video has been removed by the uploader"));

        assertThatThrownBy(() -> client.downloadAudio("https://example.com/v", workDir, 90))
                .isInstanceOf(YtDlpFailedException.class);
    }

    /** Bounded per CLAUDE.md § bound everything: long-form video costs the same as short. */
    @Test
    void downloadAudioPassesTheDurationCapAndNeverTheVideoTrack(@TempDir Path workDir) {
        runWrites(ok(), "v.m4a", "audio bytes");

        client.downloadAudio("https://example.com/v", workDir, 90);

        org.mockito.ArgumentCaptor<List<String>> command =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(processes)
                .run(command.capture(), any(Duration.class), any(Path.class));
        assertThat(command.getValue())
                .contains("-f", "bestaudio", "--download-sections", "*0-90")
                .doesNotContain("--write-subs");
    }

    /** Sanity: the command really does pass --skip-download on both paths. */
    @Test
    void neverDownloadsMedia(@TempDir Path workDir) {
        runWrites(ok(), "v.en.vtt", UPLOADED_VTT);
        when(processes.run(anyList(), any(Duration.class))).thenReturn(new ExternalProcess.Result(
                0, "{\"id\":\"x\",\"subtitles\":{},\"automatic_captions\":{}}", "", false, false));

        client.probe("https://example.com/v");
        client.fetchCaptions("https://example.com/v", workDir);

        org.mockito.ArgumentCaptor<List<String>> command =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(processes)
                .run(command.capture(), any(Duration.class));
        assertThat(command.getValue()).contains("--skip-download", "--dump-single-json");

        org.mockito.ArgumentCaptor<List<String>> captionCommand =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(processes)
                .run(captionCommand.capture(), any(Duration.class), any(Path.class));
        assertThat(captionCommand.getValue()).contains("--skip-download", "--write-auto-subs");
    }
}
