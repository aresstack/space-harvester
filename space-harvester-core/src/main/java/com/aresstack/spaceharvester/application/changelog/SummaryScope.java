package com.aresstack.spaceharvester.application.changelog;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.holdings.Selection;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Der Platz einer thematischen Zusammenfassung in der Ablage (ADR 0034 §7): ein Update einer Ebene wie bisher (ADR
 * 0030/0031) oder eine Auswahl (etwa ein Favoriten-Ordner). Eine Auswahl liegt nie nach Ordner-ID, sondern nach
 * {@code sha256(Version, caller.jobOwner(), kanonische Auswahl)}: so überschreiben sich Benutzer auf dem Server nicht
 * und sehen keine fremden Inhalte; Ordner-ID und Name sind nur Beschriftung der App.
 */
public sealed interface SummaryScope permits SummaryScope.Update, SummaryScope.Selected {

    /**
     * Stabiler, dateinamentauglicher Schlüssel des Platzes (nur Kleinbuchstaben, Ziffern und {@code -}), etwa für die
     * Podcasts zu dieser Zusammenfassung.
     */
    String key();

    /** Das Update {@code updateId} einer Quelle in einer Ebene ({@code spaceKey} {@code null}: ganze Quelle). */
    static Update update(String sourceId, String updateId, String spaceKey) {
        return new Update(sourceId, updateId, spaceKey);
    }

    /** Die Auswahl {@code selection} für den Aufrufer, mit kanonischem Schlüssel. */
    static Selected selection(AccessContext caller, Selection selection) {
        String owner = caller.jobOwner();
        return new Selected(owner, hash(Selected.VERSION + '\n' + owner + '\n' + canonical(selection)));
    }

    /**
     * Die Auswahl in kanonischer Form: Wurzeln sortiert, ohne Doppelte, je mit Unterseiten-Flag (Vorbild
     * {@code PagePodcasts.key}). Nach ADR 0034 §1 steht jede Wurzel samt Unterseiten, das Flag ist also immer
     * gesetzt; es bleibt im Schlüssel für eine spätere Wurzel „nur Seite“.
     */
    static String canonical(Selection selection) {
        return selection.roots().stream()
                .map(root -> field(root.sourceId()) + '\u001f' + field(root.spaceKey()) + '\u001f'
                        + field(root.pageId()) + '\u001f' + "subpages")
                .distinct()
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining("\n"));
    }

    private static String field(String value) {
        return value == null ? "-" : value.replace("\\", "\\\\").replace("-", "\\-").replace("\u001f", "\\u001f")
                .replace("\n", "\\n");
    }

    private static String hash(String material) {
        return Assets.sha256(material.getBytes(StandardCharsets.UTF_8)).substring("sha256:".length());
    }

    /**
     * Ein Update einer Ebene (ADR 0030/0031), wie bisher abgelegt.
     *
     * @param spaceKey der Space der Ebene, {@code null} für die ganze Quelle
     */
    record Update(String sourceId, String updateId, String spaceKey) implements SummaryScope {

        public Update {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(updateId, "updateId");
        }

        @Override
        public String key() {
            return "update-" + hash(sourceId + '\n' + updateId + '\n' + field(spaceKey));
        }
    }

    /**
     * Eine Auswahl eines Besitzers.
     *
     * @param owner  {@link AccessContext#jobOwner()} des Aufrufers, für Aufräumen und Rechte
     * @param sha256 {@code sha256(Version, owner, kanonische Auswahl)} in Hex
     */
    record Selected(String owner, String sha256) implements SummaryScope {

        /** Fassung des Schlüssels; eine neue Fassung legt neue Plätze an, alte verfallen über die Aufbewahrung. */
        static final String VERSION = "selection-summary-v1";

        public Selected {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(sha256, "sha256");
        }

        /** Ob dieser Platz dem Aufrufer gehört. */
        public boolean ownedBy(AccessContext caller) {
            return owner.equals(caller.jobOwner());
        }

        @Override
        public String key() {
            return "selection-" + sha256;
        }
    }
}
