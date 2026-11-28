package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Problems;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Die Vorschau einer Seite unter einer Trefferliste, wie im Mail-Postfach (Angelo 28.11.2026): oben der Titel,
 * darunter die Leseansicht der angeklickten Seite. Gezeigt wird der Stand aus dem Cache; nur wenn dort nichts liegt,
 * wird die Quelle gefragt, wie beim Öffnen der Seite. Gelesen wird im Hintergrund; ein neuer Klick überholt den
 * vorigen. Nur auf dem EDT.
 */
public final class PagePreview extends JPanel {

    public static final String EMPTY = "Ein Klick auf einen Treffer zeigt die Seite hier; ein Doppelklick öffnet sie.";
    static final String FAILED = "Vorschau nicht geladen: ";

    /** Liest die vorbereitete Leseansicht einer Seite; nie auf dem EDT. */
    interface Loader {
        ReadingPane.Prepared load(String sourceId, String pageId) throws Exception;
    }

    /** Führt Arbeit im Hintergrund aus und meldet das Ergebnis auf dem EDT; in Tests sofort. */
    interface Runner {
        <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    private final ReadingPane pane = new ReadingPane();
    private final JLabel title = Comic.text(" ");
    private final Loader loader;
    Runner runner = Background::run;
    /** Zählt Anfragen; die Antwort einer überholten verfällt. */
    private int request;
    private String shown;

    public PagePreview(Supplier<AppRuntime> runtime) {
        this(loader(Objects.requireNonNull(runtime, "runtime")));
    }

    PagePreview(Loader loader) {
        super(new BorderLayout());
        this.loader = Objects.requireNonNull(loader, "loader");
        setOpaque(false);
        title.setFont(Comic.semiBold(14f));
        title.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        add(title, BorderLayout.NORTH);
        add(pane, BorderLayout.CENTER);
        clear();
    }

    private static Loader loader(Supplier<AppRuntime> runtime) {
        return (sourceId, pageId) -> {
            AppRuntime current = runtime.get();
            if (current == null) {
                throw new IllegalStateException("Die Laufzeit ist nicht gestartet");
            }
            var view = current.workspace().cachedReadingView(sourceId, pageId)
                    .orElseGet(() -> current.workspace().readingView(sourceId, pageId, false));
            return PageDetailPanel.prepare(current, view);
        };
    }

    /** Zeigt die Seite; dieselbe Seite noch einmal lädt nicht neu. */
    public void show(String sourceId, String pageId, String pageTitle) {
        String key = sourceId + "\u0000" + pageId;
        if (key.equals(shown)) {
            return;
        }
        shown = key;
        int mine = ++request;
        title.setText(pageTitle == null || pageTitle.isBlank() ? " " : pageTitle);
        title.setToolTipText(pageTitle);
        pane.loading();
        runner.run(() -> loader.load(sourceId, pageId), prepared -> {
            if (mine == request) {
                pane.show(prepared);
            }
        }, failure -> {
            if (mine == request) {
                shown = null;
                pane.clear(FAILED + Problems.describe(failure).title());
            }
        });
    }

    /** Leert die Vorschau, etwa vor einer neuen Suche. */
    public void clear() {
        request++;
        shown = null;
        title.setText(" ");
        title.setToolTipText(null);
        pane.clear(EMPTY);
    }

    /** Der gezeigte Titel. */
    public String titleText() {
        return title.getText();
    }

    ReadingPane pane() {
        return pane;
    }
}
