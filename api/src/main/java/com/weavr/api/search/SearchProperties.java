package com.weavr.api.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxSemanticDistance cosine distance beyond which a vector match is
 *                            discarded as irrelevant. See
 *                            {@link SearchService} for why this is needed at
 *                            all and how the default was arrived at
 * @param candidateDepth      how deep each half searches before fusion
 */
@ConfigurationProperties(prefix = "weavr.search")
public record SearchProperties(
        double maxSemanticDistance,
        int candidateDepth
) {

    public SearchProperties {
        if (maxSemanticDistance <= 0) maxSemanticDistance = 0.40;
        if (candidateDepth <= 0) candidateDepth = 50;
    }
}
