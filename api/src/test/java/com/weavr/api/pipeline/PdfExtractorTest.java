package com.weavr.api.pipeline;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import com.weavr.api.job.PermanentJobException;
import com.weavr.api.job.RetryableJobException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The download is mocked ({@code MockRestServiceServer}); the PDF itself is
 * real, built with the same PDFBox that reads it back — a round-trip that
 * proves the extraction logic without depending on a fixture file.
 */
class PdfExtractorTest {

    private static final String URL = "https://example.com/paper.pdf";

    private MockRestServiceServer server;
    private PdfExtractor extractor;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        extractor = new PdfExtractor(builder);
    }

    private static byte[] realPdfContaining(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void extractsTextFromARealPdf() throws IOException {
        byte[] pdf = realPdfContaining("This paper presents a novel approach to widget design.");
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(pdf, MediaType.APPLICATION_PDF));

        Optional<String> text = extractor.extract(URL);

        assertThat(text).isPresent();
        assertThat(text.get()).contains("widget design");
    }

    @Test
    void returnsEmptyWhenTheDownloadIsEmpty() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK).body(new byte[0]));

        assertThat(extractor.extract(URL)).isEmpty();
    }

    @Test
    void throwsPermanentlyWhenTheBytesAreNotAValidPdf() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("not a pdf".getBytes(), MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> extractor.extract(URL))
                .isInstanceOf(PermanentJobException.class)
                .hasMessageContaining("pdf_unreadable");
    }

    @Test
    void serverErrorOnDownloadIsRetryable() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThatThrownBy(() -> extractor.extract(URL))
                .isInstanceOf(RetryableJobException.class);
    }
}
