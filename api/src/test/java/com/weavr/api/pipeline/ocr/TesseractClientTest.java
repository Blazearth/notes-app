package com.weavr.api.pipeline.ocr;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.weavr.api.pipeline.ExternalProcess;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The TSV parse, which is where every downstream decision's inputs come from.
 *
 * <p>Getting this wrong is not loud: a mean confidence computed over the layout
 * rows instead of the word rows is still a plausible-looking number, and it
 * feeds the gate that decides whether to trust the text or spend a vision
 * request on it.
 */
class TesseractClientTest {

    private static final String HEADER =
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext";

    private final ExternalProcess processes = mock(ExternalProcess.class);
    private final TesseractClient client = new TesseractClient(processes, properties());

    private static OcrProperties properties() {
        return new OcrProperties(true, "tesseract", "eng", 6, Duration.ofSeconds(30),
                90, 12, 0.25, 150, null, 60.0, 8, 0.5, 2, true, 4);
    }

    /** A word row at tesseract's level 5. */
    private static String word(int block, int par, int line, double conf, String text) {
        return "5\t1\t%d\t%d\t%d\t1\t0\t0\t10\t10\t%s\t%s".formatted(block, par, line, conf, text);
    }

    private static String tsv(String... rows) {
        return HEADER + "\n" + String.join("\n", rows) + "\n";
    }

    @Test
    void readsWordsAndAveragesOnlyOverThem() {
        String output = tsv(
                // Layout rows: tesseract emits these with conf -1 and no text.
                "1\t1\t0\t0\t0\t0\t0\t0\t100\t100\t-1\t",
                "2\t1\t1\t0\t0\t0\t0\t0\t100\t50\t-1\t",
                word(1, 1, 1, 96.0, "400g"),
                word(1, 1, 1, 90.0, "rigatoni"));

        TesseractClient.FrameText read = TesseractClient.parseTsv(output);

        assertThat(read.text()).isEqualTo("400g rigatoni");
        assertThat(read.wordCount()).isEqualTo(2);
        assertThat(read.meanConfidence()).isEqualTo(93.0);
    }

    /**
     * The -1 rows outnumber the words on a sparse card. Averaging over them
     * would drag every frame's confidence toward the floor and escalate saves
     * that never needed it.
     */
    @Test
    void ignoresRowsTesseractCouldNotRecognise() {
        String output = tsv(
                "4\t1\t1\t1\t1\t0\t0\t0\t50\t10\t-1\t",
                word(1, 1, 1, 88.0, "flour"),
                "5\t1\t1\t1\t1\t2\t0\t0\t5\t5\t-1\t");

        TesseractClient.FrameText read = TesseractClient.parseTsv(output);

        assertThat(read.wordCount()).isEqualTo(1);
        assertThat(read.meanConfidence()).isEqualTo(88.0);
    }

    /**
     * Line structure is what the voter matches on across frames. Flattened into
     * one blob, every line becomes a single giant string that agrees with
     * nothing and the vote never happens.
     */
    @Test
    void breaksLinesOnTesseractsOwnLineNumbering() {
        String output = tsv(
                word(1, 1, 1, 95.0, "400g"),
                word(1, 1, 1, 95.0, "rigatoni"),
                word(1, 1, 2, 95.0, "2"),
                word(1, 1, 2, 95.0, "tbsp"),
                word(2, 1, 1, 95.0, "GARLIC"));

        TesseractClient.FrameText read = TesseractClient.parseTsv(output);

        assertThat(read.text()).isEqualTo("400g rigatoni\n2 tbsp\nGARLIC");
    }

    @Test
    void returnsEmptyForBlankOutput() {
        assertThat(TesseractClient.parseTsv("").isEmpty()).isTrue();
        assertThat(TesseractClient.parseTsv(HEADER).isEmpty()).isTrue();
        assertThat(TesseractClient.parseTsv(null).isEmpty()).isTrue();
    }

    /** Truncated final row, which a capped process read can genuinely produce. */
    @Test
    void survivesMalformedRows() {
        String output = tsv(
                word(1, 1, 1, 91.0, "salt"),
                "5\t1\t1\t1",
                "5\t1\t1\t1\t1\t1\t0\t0\t10\t10\tnot-a-number\tpepper");

        TesseractClient.FrameText read = TesseractClient.parseTsv(output);

        assertThat(read.text()).isEqualTo("salt");
        assertThat(read.wordCount()).isEqualTo(1);
    }

    @Test
    void asksTesseractForTsvOnStdoutWithTheConfiguredPsm() {
        when(processes.run(anyList(), any(Duration.class)))
                .thenReturn(new ExternalProcess.Result(0, tsv(word(1, 1, 1, 90, "hi")), "", false, false));

        client.read(Path.of("frame.png"));

        var command = forClass(List.class);
        verify(processes).run(command.capture(), any(Duration.class));
        assertThat(command.getValue())
                .containsSubsequence("stdout", "-l", "eng")
                .containsSubsequence("--psm", "6")
                // Without this, confidence is thrown away and the gate is blind.
                .endsWith("tsv");
    }

    /**
     * One bad frame out of a dozen is normal. Failing the save over it would
     * throw away the other eleven readings of the same burned-in overlay.
     */
    @Test
    void treatsAnUnreadableFrameAsEmptyRatherThanFailing() {
        when(processes.run(anyList(), any(Duration.class)))
                .thenReturn(new ExternalProcess.Result(1, "", "Error in pixReadStream", false, false));

        assertThatCode(() -> assertThat(client.read(Path.of("frame.png")).isEmpty()).isTrue())
                .doesNotThrowAnyException();
    }
}
