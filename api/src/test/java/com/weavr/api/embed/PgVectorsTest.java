package com.weavr.api.embed;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PgVectorsTest {

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(original);
    }

    /** pgvector's literal form is exact: brackets, commas, no spaces. */
    @Test
    void writesThePgvectorLiteralForm() {
        assertThat(PgVectors.toLiteral(new float[]{0.1f, -0.25f, 3f}))
                .isEqualTo("[0.1,-0.25,3.0]");
    }

    /**
     * The bug this prevents is invisible in an English locale and total in a
     * comma-decimal one: locale-formatted output turns 0.15 into "0,15", so a
     * 1536-element vector arrives at Postgres claiming 3072 elements — and the
     * error surfaces as a dimension mismatch, pointing at the model rather than
     * at formatting.
     */
    @Test
    void isUnaffectedByACommaDecimalDefaultLocale() {
        Locale.setDefault(Locale.GERMANY);

        assertThat(PgVectors.toLiteral(new float[]{0.15f, 0.25f}))
                .isEqualTo("[0.15,0.25]")
                .doesNotContain("0,15");
    }

    @Test
    void refusesToBuildAnEmptyLiteral() {
        assertThatThrownBy(() -> PgVectors.toLiteral(new float[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PgVectors.toLiteral(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlesAFullWidthVector() {
        float[] vector = new float[1536];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = i / 1536f;
        }

        String literal = PgVectors.toLiteral(vector);

        assertThat(literal).startsWith("[").endsWith("]");
        assertThat(literal.chars().filter(c -> c == ',').count()).isEqualTo(1535);
    }
}
