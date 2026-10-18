package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Hält den {@link PageTextIndex} beim Ablegen von Seiten nach (#180): Lesetext aus dem ersten Projektor, der das Format
 * der Payload kennt; unveränderte Projektionen werden übersprungen, eine nicht mehr projizierbare Seite fällt aus dem
 * Index. Fehler bleiben hier: der Index ist neu aufbaubar und hält kein Ablegen und keinen Abgleich auf.
 */
public final class PageTextIndexing {

    private static final System.Logger LOG = System.getLogger(PageTextIndexing.class.getName());

    private final List<PageTextProjector> projectors;
    private final PageTextIndex index;

    public PageTextIndexing(List<PageTextProjector> projectors, PageTextIndex index) {
        this.projectors = List.copyOf(projectors);
        this.index = Objects.requireNonNull(index, "index");
    }

    /** Der Index, den diese Indizierung füllt. */
    public PageTextIndex index() {
        return index;
    }

    /** @return ob die Seite neu indiziert wurde */
    public boolean index(PageSnapshot snapshot) {
        return index(snapshot, snapshot.metadata());
    }

    /**
     * Wie {@link #index(PageSnapshot)}, mit den aktuellen Kopfdaten: nach einer Umbenennung oder Verschiebung ohne neue
     * Version trägt die abgelegte Payload noch die alten, der Index soll Titel und Space der aktuellen führen.
     *
     * @return ob die Seite neu indiziert wurde
     */
    public boolean index(PageSnapshot snapshot, PageMetadata current) {
        try {
            Optional<PageText> text = projection(snapshot);
            if (text.isEmpty()) {
                index.remove(snapshot.key());
                return false;
            }
            // Titel und Space gehören zum Stand des Eintrags: ändern sie sich, wird neu indiziert.
            String input = state(text.get().inputSha256(), current);
            if (index.inputSha256(snapshot.key()).filter(input::equals).isPresent()) {
                return false;
            }
            index.put(new PageTextIndex.Entry(snapshot.key(), current.partitionKey(), current.title(),
                    text.get().text(), input));
            return true;
        } catch (RuntimeException e) {
            // Ohne Meldung: sie könnte Text der Seite enthalten.
            LOG.log(System.Logger.Level.WARNING, "Volltextindex einer Seite in {0} nicht aktualisiert ({1})",
                    snapshot.key().sourceId(), e.getClass().getName());
            return false;
        }
    }

    /** Entfernt eine Seite, die es im Cache nicht mehr gibt. */
    public void remove(PageKey key) {
        try {
            index.remove(key);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Seite in {0} nicht aus dem Volltextindex entfernt ({1})",
                    key.sourceId(), e.getClass().getName());
        }
    }

    /** Macht die Änderungen dauerhaft. */
    public void flush() {
        try {
            index.flush();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Volltextindex nicht gesichert ({0})", e.getClass().getName());
        }
    }

    private static String state(String input, PageMetadata current) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((input + "\n" + current.partitionKey() + "\n"
                    + current.title()).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }

    private Optional<PageText> projection(PageSnapshot snapshot) {
        for (PageTextProjector projector : projectors) {
            Optional<PageText> text;
            try {
                text = projector.project(snapshot);
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
            if (text.isPresent()) {
                return text;
            }
        }
        return Optional.empty();
    }
}
