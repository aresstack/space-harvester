package com.aresstack.spaceharvester.application.access;

import java.util.Optional;

/**
 * Fragt eine Quelle mit dem durchgereichten Token eines Endnutzers, wer er ist und was er sehen darf (#24, Teil C).
 * Je Quelle mit Delegation genau eine Implementierung; bei Confluence etwa {@code GET /rest/api/user/current} und
 * {@code GET /rest/api/content/{id}?expand=space} ohne Body.
 *
 * <p>Nur eine bestätigt erfolgreiche Antwort der Quelle gibt frei. 401, 403, 404, Login-Redirects, Zeitüberschreitung
 * und unklare Antworten ergeben {@link AccessDecision#DENY} bzw. {@link AccessDecision#UNDETERMINED}. Das Token geht
 * nur an diese Quelle und erscheint weder in Logs noch in Ausnahmen.
 */
public interface DelegatedSourceAccess {

    /** Quellen-ID, für die diese Prüfung gilt. */
    String sourceId();

    /**
     * Die Benutzerkennung zum Token, leer wenn die Quelle das Token ablehnt.
     *
     * @throws DelegationUnavailableException wenn die Quelle nicht eindeutig antwortet
     */
    Optional<String> identify(UserCredential credential);

    /**
     * Darf der Endnutzer diesen Space bzw. diese Seite sehen? Bei einer Seite mit bekanntem Space nur, wenn sie bei der
     * Quelle auch in diesem Space liegt. Quellen-Scopes entscheidet die Richtlinie selbst.
     */
    AccessDecision canSee(UserCredential credential, ResourceScope scope);
}
