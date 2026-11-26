package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.application.extraction.DocumentContent;
import com.aresstack.spaceharvester.application.extraction.DocumentRef;
import com.aresstack.spaceharvester.deck.eakte.EakteEntry;
import com.aresstack.spaceharvester.deck.eakte.EakteSession;
import com.aresstack.spaceharvester.deck.eakte.EakteSessions;
import com.aresstack.spaceharvester.deck.eakte.EakteState;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Die E-Akte als Ablage der Dokumentanalyse (ADR 0035, Abschnitt 2): liest einen Dokumentstand
 * ({@code DocumentRef("eakte", href, ETag)}) über eine angemeldete Sitzung, nur lesend ({@code PROPFIND} der Mappe für
 * Name, Typ und ETag, dann {@code GET}). Hat sich das Dokument seither geändert (anderes ETag), wird nicht gelesen:
 * Ein Ergebnis gehört immer genau zu dem Stand, den der Benutzer sieht.
 */
final class EakteDocumentContent implements DocumentContent {


    private final EakteSessions sessions;

    EakteDocumentContent(EakteSessions sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public boolean supports(DocumentRef document) {
        return EakteBrowser.ORIGIN.equals(document.origin()) && !connected().isEmpty();
    }

    @Override
    public Content read(DocumentRef document) throws Exception {
        List<EakteSession> connected = connected();
        if (connected.isEmpty()) {
            throw new IOException("Keine angemeldete Verbindung zur E-Akte");
        }
        // Eine Anlage einer E-Mail (etwa der Erlass als PDF): die E-Mail lesen, daraus die Anlage.
        int part = document.id().indexOf(EakteBrowser.PART);
        String href = part < 0 ? document.id() : document.id().substring(0, part);
        String parent = parent(href);
        for (EakteSession session : connected) {
            Optional<EakteEntry> entry = await(session.list(parent)).stream()
                    .filter(candidate -> !candidate.folder() && candidate.href().equals(href)).findFirst();
            if (entry.isEmpty()) {
                continue;
            }
            EakteEntry found = entry.get();
            if (!document.version().isEmpty() && !document.version().equals(found.etag())) {
                throw new IOException("Das Dokument hat sich seit der Anzeige geändert (anderes ETag)");
            }
            byte[] bytes = await(session.read(href));
            if (part >= 0) {
                return attachment(found.name(), bytes, document.id().substring(part + EakteBrowser.PART.length()));
            }
            return new Content(found.name(), found.contentType(), bytes);
        }
        throw new IOException("Dokument in keiner angemeldeten Verbindung gefunden");
    }

    /** Die Anlage {@code index} der E-Mail {@code bytes}. */
    private static Content attachment(String mailName, byte[] bytes, String index) throws IOException {
        int wanted;
        try {
            wanted = Integer.parseInt(index);
        } catch (NumberFormatException e) {
            throw new IOException("Keine Anlage „" + index + "“", e);
        }
        return com.aresstack.spaceharvester.app.ui.content.viewer.MailMessage.read(mailName, bytes).attachments()
                .stream().filter(attachment -> attachment.index() == wanted).findFirst()
                .map(attachment -> new Content(attachment.name(), attachment.mediaType(), attachment.data()))
                .orElseThrow(() -> new IOException("Die E-Mail hat keine Anlage " + (wanted + 1)));
    }

    private List<EakteSession> connected() {
        return sessions.all().stream().filter(session -> session.state() == EakteState.CONNECTED).toList();
    }

    /** Die Mappe eines Dokuments: das {@code href} bis einschließlich des letzten Schrägstrichs. */
    static String parent(String href) {
        String trimmed = href.endsWith("/") ? href.substring(0, href.length() - 1) : href;
        int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? "/" : trimmed.substring(0, slash + 1);
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) throws Exception {
        // So lange darf ein Schritt (Mappe lesen, Dokument lesen) dauern; einstellbar (DeckTimeouts).
        java.time.Duration timeout = DeckTimeouts.shared().get(DeckTimeouts.Value.EAKTE_DOCUMENT);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("Die E-Akte antwortet nicht (nach " + timeout.toSeconds() + " s)", e);
        }
    }
}
