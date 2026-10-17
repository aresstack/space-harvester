package com.aresstack.spaceharvester.application.harvest;

import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Prüft einen Space vor der Annahme einer Ernte (#10): erst die Schreibweise des Keys, dann, ob die Quelle den Space
 * kennt. Abgelehnt wird nur, was eindeutig falsch ist; ist die Quelle gerade nicht erreichbar, nimmt der Dienst den
 * Auftrag an, und der Lauf meldet den Fehler selbst.
 */
public class SpaceKeyCheck {

    /**
     * Globale Confluence-Spaces: Buchstaben und Ziffern (ältere Instanzen auch {@code _}); persönliche Spaces:
     * {@code ~} und Benutzername. Länge wie Confluence höchstens 255.
     */
    private static final Pattern SPACE_KEY = Pattern.compile("[A-Za-z0-9_]{1,255}|~[A-Za-z0-9._@+-]{1,254}");

    private final SourceQueries queries;

    public SpaceKeyCheck(SourceQueries queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    /**
     * @throws InvalidQueryException  wenn der Key kein gültiger Space-Key ist
     * @throws com.aresstack.spaceharvester.application.sources.UnknownSourceException wenn die Quelle nicht konfiguriert
     *                                                                                 ist
     * @throws SourceAccessException  mit {@link SourceAccessException.Reason#NOT_FOUND}, wenn die Quelle den Space
     *                                nicht kennt oder er für das Dienstkonto nicht sichtbar ist, und mit jedem
     *                                anderen Grund außer {@link SourceAccessException.Reason#UNAVAILABLE}
     */
    public void requireExisting(String sourceId, String spaceKey) {
        if (spaceKey == null || !SPACE_KEY.matcher(spaceKey).matches()) {
            throw new InvalidQueryException("Ungültiger Space-Key");
        }
        try {
            // Ein Eintrag genügt: Die Listung meldet NOT_FOUND für einen unbekannten Space.
            queries.pageMetadata(sourceId, spaceKey, null, 1);
        } catch (SourceAccessException e) {
            // Nur eine gerade nicht erreichbare Quelle hält den Start nicht auf; der Auftrag meldet den Fehler dann
            // selbst. Fehlende Rechte, kaputte Antworten oder nicht unterstützte Quellen scheitern sofort.
            if (e.reason() != SourceAccessException.Reason.UNAVAILABLE) {
                throw e;
            }
        }
    }
}
