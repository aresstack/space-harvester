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
 */
public record SignInRequest(String id, String sourceId, String service, String title, String message, String error,
                            boolean resendAvailable, List<String> jobIds, Instant since) {

    public SignInRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceId, "sourceId");
        service = service == null ? "" : service;
        title = title == null ? "" : title;
        message = message == null ? "" : message;
        error = error == null ? "" : error;
        jobIds = List.copyOf(jobIds);
        Objects.requireNonNull(since, "since");
    }

    /** Antwort auf {@link SignInRequests#submit}. */
    public enum Answer {
        /** Angenommen: die wartenden Aufträge laden weiter, Dateien der Quelle laden ohne neue Abfrage. */
        ACCEPTED,
        /** Nicht angenommen; die Anforderung bleibt offen, {@link #error()} nennt den Grund der Quelle. */
        REJECTED,
        /** Senden gescheitert (Quelle nicht erreichbar); die Anforderung bleibt offen. */
        FAILED,
        /** Keine offene Anforderung mit dieser Kennung (mehr): angenommen, abgelehnt oder abgelaufen. */
        GONE
    }
}
