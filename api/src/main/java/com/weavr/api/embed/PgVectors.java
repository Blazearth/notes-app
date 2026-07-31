package com.weavr.api.embed;

/**
 * Converts a {@code float[]} to pgvector's text literal form.
 *
 * <p><b>Why there is no pgvector-java dependency.</b> pgvector has no Hibernate
 * type, so {@code saves.embedding} is deliberately unmapped on the {@code Save}
 * entity and read/written through {@code JdbcClient} instead. Given that, the
 * library would buy one thing — a {@code PGobject} subclass — over a parameter
 * bound as text and cast in SQL with {@code ?::vector}, which Postgres parses
 * identically. One fewer dependency, and the cast is visible in the query
 * rather than hidden in a driver type registration.
 *
 * <p>The format is exact and unforgiving: {@code [0.1,0.2,0.3]}, square
 * brackets, comma separated, no spaces.
 */
public final class PgVectors {

    private PgVectors() {
    }

    public static String toLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("refusing to build an empty vector literal");
        }
        StringBuilder sb = new StringBuilder(vector.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            // Float.toString, not a formatted decimal: locale-formatted output
            // would emit "0,15" under a comma-decimal default locale and
            // produce a literal with the wrong number of elements rather than
            // a parse error.
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
