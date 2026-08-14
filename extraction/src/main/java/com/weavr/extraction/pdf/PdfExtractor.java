package com.weavr.extraction.pdf;

import java.io.IOException;
import java.util.Optional;

import com.weavr.extraction.error.ErrorCode;
import com.weavr.extraction.error.ExtractionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Text extraction for a save whose link points straight at a PDF. Downloads
 * the file (bounded — a runaway PDF is an OOM risk the same way an unbounded
 * process read is) and strips text with PDFBox; nothing here shells out, so
 * {@link com.weavr.extraction.process.ExternalProcess} does not apply.
 *
 * <p>Ported from {@code api}'s {@code PdfExtractor}. Unlike the api version,
 * this does not yet route through {@link com.weavr.extraction.fetch.SafeUrlFetcher}
 * — the same gap the source doc flags for the monolith's own copy
 * (docs/extraction-architecture.md Phase 1: "PdfExtractor.download has the
 * identical unvalidated-fetch shape and was deliberately left untouched...
 * outside this phase's own named scope"). Carried forward here rather than
 * fixed, since fixing it means reconciling SafeUrlFetcher's streaming-abort
 * semantics with this class's own post-hoc size check, which is unrelated to
 * standing the service up.
 */
@Component
public class PdfExtractor {

    /** Generous for an article-length PDF; a bound, not a promise every PDF fits. */
    private static final int MAX_PDF_BYTES = 20 * 1024 * 1024;

    private final RestClient http;

    PdfExtractor(RestClient.Builder restClientBuilder) {
        this.http = restClientBuilder.build();
    }

    public Optional<String> extract(String url) {
        byte[] bytes = download(url);
        if (bytes.length == 0) {
            return Optional.empty();
        }
        if (bytes.length > MAX_PDF_BYTES) {
            throw new ExtractionException(ErrorCode.CONTENT_UNAVAILABLE, "That PDF is too large for Weavr to read.");
        }

        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            return text.isBlank() ? Optional.empty() : Optional.of(text.strip());
        } catch (IOException e) {
            // A PDF that does not parse is not going to parse on retry either.
            throw new ExtractionException(ErrorCode.CONTENT_UNAVAILABLE, "Weavr couldn't read that PDF.", e);
        }
    }

    private byte[] download(String url) {
        try {
            byte[] bytes = http.get().uri(url).retrieve().body(byte[].class);
            return bytes == null ? new byte[0] : bytes;
        } catch (Exception e) {
            throw new ExtractionException(ErrorCode.NETWORK_ERROR, "Could not download PDF: " + e.getMessage(), e);
        }
    }
}
