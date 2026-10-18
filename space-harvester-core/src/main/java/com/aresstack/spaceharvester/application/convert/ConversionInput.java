package com.aresstack.spaceharvester.application.convert;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.tree.PageTree;
import java.util.List;
import java.util.function.Consumer;

/** Was ein Converter lesen darf: genau die exportierten Seiten einer Cache-Generation und ihren ID-Baum. */
public interface ConversionInput {

    String sourceId();

    String spaceKey();

    /**
     * Name des Space, wie die Quelle ihn nennt (bei Confluence {@code space.name} der Seiten); ohne Namen
     * {@link #spaceKey()}. Für Überschriften und Dateinamen.
     */
    default String spaceName() {
        return spaceKey();
    }

    /** Baum der exportierten Seiten; nur Verweise über IDs. */
    PageTree tree();

    /**
     * Kopfdaten aller exportierten Seiten in Baumreihenfolge, ohne Payload; etwa um Verweise zwischen Seiten
     * aufzulösen, bevor {@link #forEachPage} sie schreibt.
     */
    List<PageHeader> headers();

    /**
     * Liefert die Seiten linear in Baumreihenfolge (Tiefensuche, Geschwister wie im Baum), jede genau einmal. Die
     * Seiten werden einzeln aus dem Cache geladen und nicht im Speicher gesammelt.
     */
    void forEachPage(Consumer<ConvertiblePage> action);

    /**
     * Die beschafften Bilder des Datenbestands ({@code assets.json} und Asset-Speicher); ohne Bilder
     * {@link ExportAssets#NONE}. Converter laden daraus, nie von der Quelle.
     */
    default ExportAssets assets() {
        return ExportAssets.NONE;
    }
}
