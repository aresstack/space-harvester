package com.aresstack.spaceharvester.application.signin;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Eine offene Anmeldeanforderung einer Quelle (ADR 0018): Aufträge warten, bis die Oberfläche den Einmalcode sendet
 * ({@link SignInRequests#submit}). Je Quelle gibt es höchstens eine; alle wartenden Aufträge stehen in
 * {@link #jobIds()}. Texte stammen von der Seite der Quelle und enthalten nie den Code.
 *
 * @param id              Kennung der Anforderung; nach Annahme, Ablehnung oder Ablauf ungültig
 * @param sourceId        Quelle, deren Sitzung freigeschaltet wird
 * @param service         wofür der Code gilt (etwa der Host)
 * @param title           Überschrift der Seite der Quelle, darf leer sein
 * @param message         Hinweis der Quelle (etwa „Code per E-Mail gesendet“), darf leer sein
 * @param error           Fehlermeldung des letzten Versuchs (etwa „Code ungültig“), sonst leer
 * @param resendAvailable ob „Code erneut senden“ möglich ist
 * @param jobIds          Aufträge, die an dieser Stelle warten („Wartet auf Anmeldung“)
 * @param since           seit wann die Anforderung offen ist
 * @param failure         warum das letzte Senden oder Anfordern eines Codes gescheitert ist (#571), sonst leer;
 *                        kurz und bereinigt aus {@link SignInFailures#describe}, etwa
 *                        {@code Proxy verlangt Anmeldung (HTTP 407) – Ziel wiki.example}: Art des Fehlers (HTTP-Status,
 *                        Proxy-407, abgelehnter Tunnel, TLS, Zeitüberschreitung, Verbindungsfehler) und Ziel-Host, nie
 *                        Code, Cookies, Kopfzeilen oder Query. Anders als {@link #error()} stammt der Text nicht von
 *                        der Seite der Quelle; ein Code, den die Quelle ablehnt, setzt {@link #error()}, nicht diesen
 *                        Wert. Ein späteres gelungenes Senden oder Anfordern leert ihn.
 * @param asksForLogin    ob neben dem Code einmalig Benutzer und Passwort abgefragt werden (Quelle mit Personal Access
 *                        Token, #571); gesendet mit {@link SignInRequests#submit(String, char[], String, char[])}
 */
public record SignInRequest(String id, String sourceId, String service, String title, String message, String error,
                            boolean resendAvailable, List<String> jobIds, Instant since, String failure,
                            boolean asksForLogin) {

    public SignInRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceId, "sourceId");
        service = service == null ? "" : service;
        title = title == null ? "" : title;
        message = message == null ? "" : message;
        error = error == null ? "" : error;
        jobIds = List.copyOf(jobIds);
        Objects.requireNonNull(since, "since");
        failure = failure == null ? "" : failure;
    }

    /** Ohne Abfrage von Benutzer und Passwort; die Form vor #603. */
    public SignInRequest(String id, String sourceId, String service, String title, String message, String error,
                         boolean resendAvailable, List<String> jobIds, Instant since, String failure) {
        this(id, sourceId, service, title, message, error, resendAvailable, jobIds, since, failure, false);
    }

    /** Ohne gescheitertes Senden ({@link #failure()} leer); die Form vor #571. */
    public SignInRequest(String id, String sourceId, String service, String title, String message, String error,
                         boolean resendAvailable, List<String> jobIds, Instant since) {
        this(id, sourceId, service, title, message, error, resendAvailable, jobIds, since, "");
    }

    /** Antwort auf {@link SignInRequests#submit}. */
    public enum Answer {
        /** Angenommen: die wartenden Aufträge laden weiter, Dateien der Quelle laden ohne neue Abfrage. */
        ACCEPTED,
        /** Nicht angenommen; die Anforderung bleibt offen, {@link #error()} nennt den Grund der Quelle. */
        REJECTED,
        /**
         * Nur angemeldet (erster Schritt bei einem Token, {@link SignInRequests#beginWebLogin}); die Anforderung
         * fragt jetzt den Code der neuen Web-Sitzung ab.
         */
        NEXT_STEP,
        /**
         * Senden gescheitert (keine Verbindung, Proxy, TLS, HTTP-Fehler der Quelle …); die Anforderung bleibt offen,
         * {@link #failure()} nennt die Ursache.
         */
        FAILED,
        /** Keine offene Anforderung mit dieser Kennung (mehr): angenommen, abgelehnt oder abgelaufen. */
        GONE
    }
}
