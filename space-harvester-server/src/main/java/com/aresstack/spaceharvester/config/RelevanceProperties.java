package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.ranking.ReciprocalRankFusion;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Konfiguration unter {@code space-harvester.relevance}: Fusion der hybriden Relevanzsuche (#181), wie
 * {@code retrieval.keywordWeight}, {@code retrieval.semanticWeight} und {@code retrieval.rankConstant} im Enterprise AI
 * Client.
 *
 * @param keywordWeight  Gewicht der Volltext-Rangfolge, 0 bis 10
 * @param semanticWeight Gewicht der Kosinus-Rangfolge, 0 bis 10
 * @param rankConstant   {@code k} der Reciprocal Rank Fusion, 1 bis 1000
 */
@ConfigurationProperties("space-harvester.relevance")
public record RelevanceProperties(@DefaultValue("1.0") double keywordWeight, @DefaultValue("1.0") double semanticWeight,
                                  @DefaultValue("60") int rankConstant) {

    ReciprocalRankFusion fusion() {
        return new ReciprocalRankFusion(keywordWeight, semanticWeight, rankConstant);
    }
}
