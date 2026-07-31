package com.weavr.api.act;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;

/**
 * Combines two quantities of the same ingredient.
 *
 * <p>The case this exists for: two recipes both want onions, and the list
 * should say <em>3 onions</em> rather than listing onions twice. But recipe
 * quantities are prose — "a pinch", "2-3 cloves", "1 tbsp plus extra for
 * greasing" — so the rule is deliberately narrow:
 *
 * <ul>
 *   <li><b>Add them</b> only when both sides are unambiguous numbers (including
 *       simple and mixed fractions, which recipes use constantly) and the units
 *       match.</li>
 *   <li><b>Otherwise join with " + "</b> and let the reader do it. "a pinch +
 *       1/2 tsp" is honest and shoppable; silently dropping one side or
 *       inventing a total is not.</li>
 * </ul>
 *
 * <p>No unit conversion. Turning tablespoons into cups needs a density table
 * for anything measured by weight, and getting it subtly wrong produces a
 * plausible number that is simply incorrect — the same failure mode as an OCR
 * misread the model "repairs" into confident nonsense.
 */
final class Quantities {

    private Quantities() {
    }

    /**
     * @return the merged quantity text, or empty when neither side had one
     */
    static Optional<String> merge(String leftQuantity, String leftUnit,
                                  String rightQuantity, String rightUnit) {
        String left = normalise(leftQuantity);
        String right = normalise(rightQuantity);

        if (left == null) return Optional.ofNullable(right);
        if (right == null) return Optional.of(left);

        if (sameUnit(leftUnit, rightUnit)) {
            Optional<BigDecimal> a = parse(left);
            Optional<BigDecimal> b = parse(right);
            if (a.isPresent() && b.isPresent()) {
                return Optional.of(format(a.get().add(b.get())));
            }
        }

        // Already mentioned — "2 + 2" from adding the same recipe twice is
        // noise, and re-adding a recipe is a normal thing to do.
        if (left.equalsIgnoreCase(right)) {
            return Optional.of(left);
        }
        return Optional.of(left + " + " + right);
    }

    private static String normalise(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Units match if they are equal ignoring case, spacing and a trailing plural "s". */
    static boolean sameUnit(String left, String right) {
        return canonicalUnit(left).equals(canonicalUnit(right));
    }

    private static String canonicalUnit(String unit) {
        if (unit == null) return "";
        String u = unit.trim().toLowerCase(Locale.ROOT).replace(".", "");
        // "cups" and "cup" are the same unit; "g" and "gs" never both occur, so
        // stripping a trailing s is safe on the vocabulary recipes actually use.
        if (u.length() > 1 && u.endsWith("s")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /**
     * Parses the numeric forms recipes actually use: {@code 2}, {@code 1.5},
     * {@code 1/2}, {@code 1 1/2}. Anything else — ranges like {@code 2-3},
     * words like {@code a pinch}, or trailing prose — returns empty, which is
     * what routes the pair to string concatenation instead of arithmetic.
     */
    static Optional<BigDecimal> parse(String value) {
        String v = value.trim();
        if (v.isEmpty()) return Optional.empty();

        // Mixed number: "1 1/2"
        String[] parts = v.split("\\s+");
        if (parts.length == 2) {
            Optional<BigDecimal> whole = plain(parts[0]);
            Optional<BigDecimal> fraction = fraction(parts[1]);
            if (whole.isPresent() && fraction.isPresent()) {
                return Optional.of(whole.get().add(fraction.get()));
            }
            return Optional.empty();
        }
        if (parts.length != 1) return Optional.empty();

        Optional<BigDecimal> asFraction = fraction(v);
        return asFraction.isPresent() ? asFraction : plain(v);
    }

    private static Optional<BigDecimal> plain(String value) {
        try {
            return Optional.of(new BigDecimal(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<BigDecimal> fraction(String value) {
        int slash = value.indexOf('/');
        if (slash <= 0 || slash == value.length() - 1) return Optional.empty();
        Optional<BigDecimal> numerator = plain(value.substring(0, slash));
        Optional<BigDecimal> denominator = plain(value.substring(slash + 1));
        if (numerator.isEmpty() || denominator.isEmpty()
                || denominator.get().signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(numerator.get().divide(denominator.get(), 4, RoundingMode.HALF_UP));
    }

    /** Trailing zeros stripped, so 2 + 2 reads "4" rather than "4.0000". */
    private static String format(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() <= 0
                ? stripped.toBigInteger().toString()
                : stripped.toPlainString();
    }
}
