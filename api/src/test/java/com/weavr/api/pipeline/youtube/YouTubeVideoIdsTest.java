package com.weavr.api.pipeline.youtube;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeVideoIdsTest {

    private static final String ID = "dQw4w9WgXcQ";

    /** Every shape the original RapidYtClient regex accepted must still resolve. */
    @Test
    void keepsEveryShapeThePreviousMatcherAccepted() {
        assertThat(YouTubeVideoIds.parse("https://youtube.com/watch?v=" + ID)).isEqualTo(ID);
        assertThat(YouTubeVideoIds.parse("https://www.youtube.com/watch?v=" + ID)).isEqualTo(ID);
        assertThat(YouTubeVideoIds.parse("https://m.youtube.com/watch?v=" + ID)).isEqualTo(ID);
        assertThat(YouTubeVideoIds.parse("https://music.youtube.com/watch?v=" + ID)).isEqualTo(ID);
        assertThat(YouTubeVideoIds.parse("https://youtu.be/" + ID)).isEqualTo(ID);
        assertThat(YouTubeVideoIds.parse("https://youtube.com/shorts/" + ID)).isEqualTo(ID);
        assertThat(YouTubeVideoIds.parse("https://youtube.com/embed/" + ID)).isEqualTo(ID);
        assertThat(YouTubeVideoIds.parse("https://youtube.com/live/" + ID)).isEqualTo(ID);
    }

    @Test
    void resolvesShapesThePreviousMatcherMissed() {
        // v= not first — the common "share" link shape.
        assertThat(YouTubeVideoIds.parse("https://www.youtube.com/watch?feature=share&v=" + ID)).isEqualTo(ID);
        assertThat(YouTubeVideoIds.parse("https://www.youtube-nocookie.com/embed/" + ID)).isEqualTo(ID);
        assertThat(YouTubeVideoIds.parse("https://www.youtube.com/v/" + ID)).isEqualTo(ID);
    }

    @Test
    void ignoresTrailingQueryAndWhitespace() {
        assertThat(YouTubeVideoIds.parse("https://youtu.be/" + ID + "?si=abc123&t=42")).isEqualTo(ID);
        assertThat(YouTubeVideoIds.parse("https://youtube.com/shorts/" + ID + "?feature=share")).isEqualTo(ID);
        assertThat(YouTubeVideoIds.parse("  https://www.youtube.com/watch?v=" + ID + "&t=10s  ")).isEqualTo(ID);
    }

    @Test
    void rejectsNonVideoAndNonYoutubeUrls() {
        assertThat(YouTubeVideoIds.parse(null)).isNull();
        assertThat(YouTubeVideoIds.parse("")).isNull();
        assertThat(YouTubeVideoIds.parse("https://example.com/article")).isNull();
        assertThat(YouTubeVideoIds.parse("https://vimeo.com/12345678")).isNull();
        assertThat(YouTubeVideoIds.parse("https://www.youtube.com/@somechannel")).isNull();
        assertThat(YouTubeVideoIds.parse("https://www.youtube.com/playlist?list=PL123")).isNull();
        assertThat(YouTubeVideoIds.parse("https://www.tiktok.com/@someone/video/7123456789012345678")).isNull();
    }

    @Test
    void rejectsIdsOfTheWrongShape() {
        assertThat(YouTubeVideoIds.parse("https://www.youtube.com/watch?v=short")).isNull();
        assertThat(YouTubeVideoIds.parse("https://youtu.be/has space!!")).isNull();
        assertThat(YouTubeVideoIds.isValidId(ID)).isTrue();
        assertThat(YouTubeVideoIds.isValidId("dQw4w9WgXc")).isFalse();
        assertThat(YouTubeVideoIds.isValidId("dQw4w9WgXcQ1")).isFalse();
        assertThat(YouTubeVideoIds.isValidId("dQw4w9WgX/Q")).isFalse();
    }
}
