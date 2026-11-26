package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.deck.ndv.NdvProfile;
import java.awt.Component;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Anschluss des NDV-Bereichs an die globale Suche und die KI-Suche wie „Indexieren (Regel anlegen)“ und „Jetzt
 * indexieren“ der {@code IndexingSidebar} in MainframeMate. Im Space Harvester ist die Regel eine Wissensquelle vom Typ
 * {@code ndv} (Host, Port, KeePass-Eintrag, Bibliotheken); ihr Abgleich indiziert die Quelltexte im Lucene-Index des
 * Core, den die globale Suche und der Chat durchsuchen. Die Umsetzung liegt bei den Wissensquellen der App; das Deck
 * kennt nur diesen Port.
 */
public interface NdvSearchLink {

    /**
     * Die Regel eines Servers: die Wissensquelle und die Bibliotheken, die sie indiziert.
     *
     * @param libraries Bibliotheken in Großbuchstaben, ohne Filter ({@code LIB/PREFIX*} ergibt {@code LIB})
     */
    record Rule(String sourceId, String title, List<String> libraries) {

        public Rule {
            libraries = List.copyOf(libraries);
        }

        /** Ob die Regel diese Bibliothek indiziert. */
        public boolean covers(String library) {
            return library != null && libraries.contains(library.strip().toUpperCase(Locale.ROOT));
        }
    }

    /** Ohne Wissensquellen (Tests, Deck ohne App): keine Regel, Anlegen meldet das. */
    NdvSearchLink NONE = new NdvSearchLink() {
        @Override
        public Optional<Rule> rule(NdvProfile profile) {
            return Optional.empty();
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public void createRule(Component parent, NdvProfile profile, String account, List<String> libraries,
                               Consumer<String> saved) {
            throw new IllegalStateException("Ohne Wissensquellen lässt sich keine Regel anlegen");
        }

        @Override
        public String indexNow(String sourceId) {
            throw new IllegalStateException("Ohne Wissensquellen lässt sich nichts indizieren");
        }

        @Override
        public void observe(Runnable changed) {
        }
    };

    /** Die Wissensquelle vom Typ {@code ndv} für diesen Server (gleicher Host und Port), falls eine besteht. */
    Optional<Rule> rule(NdvProfile profile);

    default Optional<Rule> rule(NdvProfile profile, String account) {
        return rule(profile);
    }

    /** Ob Regeln angelegt werden können (die App mit Wissensquellen läuft). */
    boolean available();

    /**
     * „Indexieren (Regel anlegen)“: Gibt es für den Server schon eine Wissensquelle, öffnet das Quellenformular sie mit
     * den Bibliotheken ergänzt; sonst eine neue mit Host, Port, KeePass-Eintrag und Bibliotheken vorbelegt. Nach dem
     * Speichern erhält {@code saved} die Kennung der Quelle.
     *
     * @param account Titel des KeePass-Eintrags der Sitzung oder leer
     */
    void createRule(Component parent, NdvProfile profile, String account, List<String> libraries,
                    Consumer<String> saved);

    /**
     * „Jetzt indexieren“: startet den Abgleich der Wissensquelle (eigener Auftrag in der Auftragsliste) und liefert
     * eine Zeile für die Statuszeile. Blockiert nicht lange, läuft aber nie auf dem EDT.
     */
    String indexNow(String sourceId) throws Exception;

    /** Meldet geänderte Wissensquellen (etwa nach dem Speichern einer Regel); kein Polling. */
    void observe(Runnable changed);
}
