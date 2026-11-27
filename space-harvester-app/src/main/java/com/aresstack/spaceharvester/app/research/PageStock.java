package com.aresstack.spaceharvester.app.research;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageKey;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Der schmale Blick der Recherche auf den Bestand von Space Harvester: suchen wie die Suchleiste, lesen wie der Chat,
 * Unterseiten nennen und eine Seite freigeben, bevor ihr Text an das Modell geht. Für Tests austauschbar; produktiv
 * {@link WorkspacePageStock}.
 */
public interface PageStock {

    /**
     * Eine lesbare Seite.
     *
     * @param spaceKey Space laut Kopfdaten; {@code null}, wenn die Quelle keinen nennt
     * @param location Adresse der Seite bei der Quelle; {@code null}, wenn unbekannt
     * @param reading  der Lesetext mit Abschnitten (Überschriften) für die Passagen der Buchfunktion; {@code null},
     *                 wenn die Quelle keinen liefert (dann gilt {@code text} ohne Überschriften)
     */
    record Page(PageKey key, String title, String text, String spaceKey, String location, PageText reading) {

        public Page(PageKey key, String title, String text, String spaceKey, String location) {
            this(key, title, text, spaceKey, location, null);
        }
    }

    /** Eine Unterseite, wie die Navigation der Quelle sie nennt. */
    record Child(PageKey key, String title) {
    }

    /** Dieselbe Schlagwortsuche wie die Suchleiste; höchstens {@code limit} Treffer, beste zuerst. */
    List<KeywordSearch.Hit> search(String text, int limit);

    /** Die Seite aus dem Cache oder von der Quelle geholt; leer, wenn sie danach nicht lokal liegt. Wirft beim Abruf. */
    Optional<Page> page(PageKey key);

    /**
     * Die Version der abgelegten Seite (Stand der Quellen einer Buchauflage); {@code null}, wenn die Ablage keine
     * kennt. Fragt nie die Quelle.
     */
    default String version(PageKey key) {
        return null;
    }

    /** Die Unterseiten in der Reihenfolge der Quelle; leer bei Quellen ohne Seitenbaum. */
    List<Child> children(PageKey key, String spaceKey);

    /**
     * Trägt Seiten in die Liste freigegebener Seiten ein (KI-Freigabe), alle in einer Änderung; wirft, wenn das
     * scheitert.
     *
     * @param spaceOfPage Seite → Space der Seite
     */
    void release(Map<PageKey, String> spaceOfPage);
}
