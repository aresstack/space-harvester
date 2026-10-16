package com.aresstack.spaceharvester.application.convert;

import com.aresstack.spaceharvester.application.tree.PageTree;
import java.util.function.Consumer;

/** Was ein Converter lesen darf: genau die exportierten Seiten einer Cache-Generation und ihren ID-Baum. */
public interface ConversionInput {

    String sourceId();

    String spaceKey();

    /** Baum der exportierten Seiten; nur Verweise über IDs. */
    PageTree tree();

    /**
     * Liefert die Seiten linear in Baumreihenfolge (Tiefensuche, Geschwister wie im Baum), jede genau einmal. Die
     * Seiten werden einzeln aus dem Cache geladen und nicht im Speicher gesammelt.
     */
    void forEachPage(Consumer<ConvertiblePage> action);
}
