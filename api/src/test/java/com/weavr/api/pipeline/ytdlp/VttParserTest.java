package com.weavr.api.pipeline.ytdlp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VttParserTest {

    @Test
    void stripsHeadersTimestampsAndCueNumbers() {
        String vtt = """
                WEBVTT
                Kind: captions
                Language: en

                1
                00:00:01.000 --> 00:00:03.500
                Add two tablespoons of miso paste.

                2
                00:00:03.500 --> 00:00:06.000
                Then whisk until smooth.
                """;

        assertThat(VttParser.toPlainText(vtt))
                .isEqualTo("Add two tablespoons of miso paste. Then whisk until smooth.");
    }

    @Test
    void stripsInlineKaraokeTimingTags() {
        String vtt = """
                WEBVTT

                00:00:01.000 --> 00:00:03.000
                <00:00:01.100><c>Add</c> <00:00:01.400><c>the</c> <00:00:01.700><c>garlic</c>
                """;

        assertThat(VttParser.toPlainText(vtt)).isEqualTo("Add the garlic");
    }

    /**
     * The case that actually matters. YouTube's rolling window repeats each line
     * across consecutive cues so the text appears to scroll; a naive strip
     * triples the transcript.
     */
    @Test
    void collapsesRollingWindowRepetition() {
        String vtt = """
                WEBVTT

                00:00:01.000 --> 00:00:02.000
                first you brown

                00:00:02.000 --> 00:00:03.000
                first you brown the onions

                00:00:03.000 --> 00:00:04.000
                first you brown the onions
                then add stock
                """;

        assertThat(VttParser.toPlainText(vtt))
                .isEqualTo("first you brown the onions then add stock");
    }

    @Test
    void dropsExactConsecutiveDuplicates() {
        String vtt = """
                WEBVTT

                00:00:01.000 --> 00:00:02.000
                simmer for ten minutes

                00:00:02.000 --> 00:00:03.000
                simmer for ten minutes
                """;

        assertThat(VttParser.toPlainText(vtt)).isEqualTo("simmer for ten minutes");
    }

    @Test
    void decodesHtmlEntities() {
        String vtt = """
                WEBVTT

                00:00:01.000 --> 00:00:02.000
                salt &amp; pepper &quot;to taste&quot;
                """;

        assertThat(VttParser.toPlainText(vtt)).isEqualTo("salt & pepper \"to taste\"");
    }

    @Test
    void handlesShortTimestampsWithoutHours() {
        String vtt = """
                WEBVTT

                01:02.500 --> 01:05.000
                Reduce the heat.
                """;

        assertThat(VttParser.toPlainText(vtt)).isEqualTo("Reduce the heat.");
    }

    @Test
    void returnsEmptyForNullOrBlankInput() {
        assertThat(VttParser.toPlainText(null)).isEmpty();
        assertThat(VttParser.toPlainText("   ")).isEmpty();
        assertThat(VttParser.toPlainText("WEBVTT\n\n")).isEmpty();
    }
}
