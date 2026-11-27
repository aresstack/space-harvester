package com.aresstack.spaceharvester.app.research;

import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.policy.RunGuard;
import com.aresstack.knowledge4j.policy.TransferPolicy;
import com.aresstack.knowledge4j.policy.TransferTarget;
import com.aresstack.knowledge4j.source.Classification;
import com.aresstack.knowledge4j.source.Provenance;
import com.aresstack.research4j.acquire.FetchChannel;
import com.aresstack.research4j.acquire.SearchChannel;
import com.aresstack.research4j.basket.BasketResearch;
import com.aresstack.research4j.basket.SourceKeeper;

import java.util.List;

/**
 * Verdrahtet Research4j mit dem Bestand von Space Harvester: die interne Suche als einziger Suchweg, das Lesen der
 * Seiten als einziger Abrufweg, beide intern. Der Auftrag gilt als intern (ohne ausdrückliche Freigabe), die Regel ist
 * „intern bleibt intern“: Eine Websuche bekäme keine Anfrage, solange sie nicht ausdrücklich als Weg dazukommt.
 */
public final class HostResearch {

    /** Kennung der internen Suche für die Weitergabe-Regel und den Bericht. */
    public static final String INDEX = "space-harvester-suche";
    /** Kennung des Seitenabrufs für die Weitergabe-Regel und den Bericht. */
    public static final String PAGES = "space-harvester-seiten";

    private HostResearch() {
    }

    /**
     * @param hitsPerQuery Treffer je Anfrage, eine Einstellung
     */
    public static BasketResearch research(MainModelChat chat, PageStock stock, SourceKeeper keeper, int hitsPerQuery,
                                          BasketResearch.Settings settings) {
        RunGuard guard = new RunGuard(TransferPolicy.internalStaysInternal(),
                Provenance.classified(Classification.INTERNAL));
        return new BasketResearch(chat,
                List.of(new SearchChannel(TransferTarget.internal(INDEX), new IndexSearchPort(stock), hitsPerQuery)),
                List.of(new FetchChannel(TransferTarget.internal(PAGES), new PageFetchPort(stock))),
                keeper, guard, settings);
    }
}
