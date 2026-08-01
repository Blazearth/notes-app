package com.weavr.api.space;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared-Space behaviour.
 *
 * <p>{@code duplicateMaxDistance} is the only interesting value here, and like
 * the OCR confidence floor and the search cutoff before it, <b>it is a guess</b>
 * — argued from the search half's measurements rather than from a corpus of
 * real duplicate pairs. It lives in configuration precisely so the guess is one
 * number in one place a real Space can correct.
 */
@ConfigurationProperties(prefix = "weavr.spaces")
public record SpaceProperties(

        boolean duplicateDetectionEnabled,

        /**
         * Maximum cosine distance for two saves to be suggested as duplicates.
         *
         * <p>Tighter than the search cutoff (0.40) by a wide margin, and it has
         * to be: a search query merely <em>describes</em> a save, where two
         * duplicates <em>are</em> the same thing. Real query hits measured at
         * 0.27–0.37, so anything in that range would suggest every restaurant
         * in a Space is the same restaurant.
         */
        double duplicateMaxDistance,

        /**
         * Cap per newly embedded save. A Space with forty similar restaurants
         * should produce a prompt, not forty of them.
         */
        int duplicateMaxSuggestions
) {
}
