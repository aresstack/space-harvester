package com.aresstack.spaceharvester.application.presentation;

import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.observe.Coalescer;
import com.aresstack.spaceharvester.application.observe.Listeners;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Hält die Analysen einer Videoseite beim Transkript (Angelo 20.11.2026): Wird Transkript oder Korrektur abgelegt,
 * rechnen die Analysen über den neuen Text ({@link VideoTranscriptTextProjector}); wird es gelöscht, fallen sie weg.
 * Meldungen kommen aus der Ablage (ADR 0027, kein Polling) und werden gebündelt: Während Whisper das Transkript
 * Stück für Stück ablegt, rechnet es höchstens einmal je {@link #INTERVAL}.
 */
public final class TranscriptAnalyses implements AutoCloseable {

    /** Höchstens eine Berechnung je Seite in diesem Abstand. */
    public static final Duration INTERVAL = Duration.ofSeconds(10);

    private static final System.Logger LOG = System.getLogger(TranscriptAnalyses.class.getName());

    private final Enrichments enrichments;
    private final Function<PageKey, Optional<PageSnapshot>> pages;
    private final Coalescer<PageKey, Void> pending;
    private final Listeners<java.util.function.Consumer<PageKey>> listeners =
            new Listeners<>("Analysen zum Transkript");

    /**
     * @param enrichments die Analysen des Core
     * @param pages       die Seite im Cache
     */
    public TranscriptAnalyses(Enrichments enrichments, Function<PageKey, Optional<PageSnapshot>> pages) {
        this(enrichments, pages, INTERVAL);
    }

    TranscriptAnalyses(Enrichments enrichments, Function<PageKey, Optional<PageSnapshot>> pages, Duration interval) {
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.pending = new Coalescer<>("transcript-analyses", interval, (key, ignored) -> update(key));
    }

    /** Merkt die Seite vor; gerechnet wird gebündelt auf einem eigenen Thread. */
    public void changed(PageKey key) {
        pending.mark(key);
    }

    /**
     * Bringt die Analysen der Seite sofort auf den Stand ihres Transkripts: rechnet, was fehlt oder veraltet ist, und
     * entfernt sie, wenn es keinen Text mehr gibt.
     *
     * @return ob die Seite jetzt Text hat
     */
    public boolean update(PageKey key) {
        Optional<PageSnapshot> snapshot = pages.apply(key);
        if (snapshot.isEmpty()) {
            return false;
        }
        try {
            Enrichments.Run run = enrichments.refresh(snapshot.get());
            if (!run.supported()) {
                enrichments.forget(key);
            }
            enrichments.flush();
            listeners.deliver(key, listener -> listener.accept(key));
            return run.supported();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Analysen zum Transkript in {0} nicht berechnet ({1})",
                    key.sourceId(), e.getClass().getName());
            return false;
        }
    }

    /**
     * Meldet sich, nachdem die Analysen einer Seite zu ihrem Transkript neu gerechnet oder entfernt wurden, damit eine
     * offene Ansicht neu liest (ADR 0027, kein Polling). Gerufen auf dem rechnenden Thread.
     */
    public Subscription subscribe(java.util.function.Consumer<PageKey> listener) {
        return listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void close() {
        pending.close();
    }
}
