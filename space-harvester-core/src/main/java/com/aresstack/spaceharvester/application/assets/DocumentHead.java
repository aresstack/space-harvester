package com.aresstack.spaceharvester.application.assets;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Kopfdaten eines Dokuments (ADR 0026, Punkt 3), getrennt von den Blöcken: Sie gehen nicht in den Lesetext der
 * Analysen. Jedes Feld ist optional und nur gesetzt, wenn die Datei es enthält.
 *
 * @param title   Titel aus den Metadaten der Datei
 * @param subject Betreff einer Mail
 * @param from    Absender einer Mail
 * @param to      Empfänger einer Mail
 * @param date    Datum (bei Mails das Sendedatum)
 */
public record DocumentHead(Optional<String> title, Optional<String> subject, Optional<String> from,
                           List<String> to, Optional<Instant> date) {

    public static final DocumentHead EMPTY = new DocumentHead(Optional.empty(), Optional.empty(), Optional.empty(),
            List.of(), Optional.empty());

    public DocumentHead {
        title = blankless(title);
        subject = blankless(subject);
        from = blankless(from);
        to = List.copyOf(to == null ? List.of() : to);
        date = date == null ? Optional.empty() : date;
    }

    private static Optional<String> blankless(Optional<String> value) {
        return value == null ? Optional.empty() : value.filter(text -> !text.isBlank());
    }
}
