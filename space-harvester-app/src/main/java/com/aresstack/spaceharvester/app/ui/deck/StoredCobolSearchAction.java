package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.content.StoredCobolSearchDialog;
 import java.awt.Component;
import java.util.concurrent.Future;
import javax.swing.JComboBox;

/** Use the persisted source selector and shared query dialog independently of FTP sessions. */
public final class StoredCobolSearchAction {
    private final AppContext context;
    private final Component parent;
    private final java.util.function.BooleanSupplier active;
    private final java.util.function.Consumer<Boolean> busy;
    private Future<?> task;
    private long generation;

    public StoredCobolSearchAction(AppContext context, Component parent,
                                   java.util.function.BooleanSupplier active,
                                   java.util.function.Consumer<Boolean> busy) {
        this.context = context;
        this.parent = parent;
        this.active = active;
        this.busy = busy;
    }

    public void open() {
        if (task != null) {
            generation++;
            task.cancel(true);
            task = null;
            busy.accept(false);
            return;
        }
        AppRuntime runtime = context.runtime();
        if (runtime == null || !active.getAsBoolean()) return;
        var sources = runtime.workspace().sources().stream()
                .filter(source -> "ftp".equalsIgnoreCase(source.type())
                        && runtime.workspace().sourceEnabled(source.id())
                        && !runtime.workspace().sourceDataBeingDeleted(source.id()))
                .map(source -> new Choice(source.id(), source.title())).toList();
        if (sources.isEmpty()) {
            Dialogs.view(parent, "COBOL-Suche", Comic.note("Keine aktivierte FTP-Wissensquelle vorhanden."));
            return;
        }
        var choices = new JComboBox<>(sources.toArray(Choice[]::new));
        if (!Dialogs.formOverlay(parent, "FTP-Wissensquelle auswählen", choices, "Suchen")) return;
        var chosen = (Choice) choices.getSelectedItem();
        if (chosen == null || context.runtime() != runtime) return;
        long request = ++generation;
        busy.accept(true);
        task = Background.start(() -> runtime.workspace().storedCobolSearch(chosen.id(),
                () -> Thread.currentThread().isInterrupted() || !active.getAsBoolean()
                        || context.runtime() != runtime), snapshot -> {
            if (request != generation) return;
            task = null;
            busy.accept(false);
            if (context.runtime() != runtime || !active.getAsBoolean()) return;
            new StoredCobolSearchDialog(snapshot).show(parent).ifPresent(fact -> {
                if (context.runtime() != runtime || request != generation || !active.getAsBoolean()) return;
                String source = fact.attributes().get("SOURCE_ID");
                String id = fact.attributes().get("PAGE_ID");
                String hash = fact.attributes().get("SOURCE_RAW_SHA256");
                if (source == null || id == null || hash == null) {
                    Dialogs.view(parent, "COBOL-Suche", Comic.note("Keine verifizierte Quellposition verfügbar."));
                    return;
                }
                context.navigator().openProgram(source, id, hash, fact.line());
            });
        }, error -> {
            if (request != generation) return;
            task = null;
            busy.accept(false);
            if (context.runtime() == runtime && active.getAsBoolean()
                    && !(error instanceof java.util.concurrent.CancellationException)) {
                Dialogs.view(parent, "COBOL-Suche", Comic.note("Suche fehlgeschlagen."));
            }
        });
    }

    private record Choice(String id, String title) {
        @Override public String toString() { return title + " (" + id + ")"; }
    }
}
