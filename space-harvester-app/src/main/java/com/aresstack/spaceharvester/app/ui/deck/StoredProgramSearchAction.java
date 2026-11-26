package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.content.StoredProgramSearchDialog;
import java.awt.Component;
import java.util.Set;
import java.util.concurrent.Future;

/** Open the common program search independently of live mainframe sessions. */
public final class StoredProgramSearchAction {
    private final AppContext context;
    private final Component parent;
    private final java.util.function.BooleanSupplier active;
    private final java.util.function.Consumer<Boolean> busy;
    private Future<?> task;
    private long generation;
    private boolean dialogOpen;

    public StoredProgramSearchAction(AppContext context, Component parent,
                                   java.util.function.BooleanSupplier active,
                                   java.util.function.Consumer<Boolean> busy) {
        this.context = context;
        this.parent = parent;
        this.active = active;
        this.busy = busy;
    }

    public void open() {
        open(null);
    }

    public void cancel() {
        generation++;
        if (task != null) {
            task.cancel(true);
            task = null;
            busy.accept(false);
        }
    }

    public void open(String initialSource) {
        if (dialogOpen) return;
        if (task != null) {
            cancel();
            return;
        }
        AppRuntime runtime = context.runtime();
        if (runtime == null || !active.getAsBoolean()) return;
        var sources = runtime.workspace().programSearchSources();
        if (sources.isEmpty()) {
            Dialogs.view(parent, "Programmsuche", Comic.note("Keine lesbare FTP- oder NDV-Wissensquelle vorhanden."));
            return;
        }
        long request = ++generation;
        busy.accept(true);
        task = Background.start(() -> runtime.workspace().storedProgramSearch(Set.copyOf(sources),
                () -> Thread.currentThread().isInterrupted() || !active.getAsBoolean()
                        || context.runtime() != runtime), snapshot -> {
            if (request != generation) return;
            task = null;
            busy.accept(false);
            if (context.runtime() != runtime || !active.getAsBoolean()) return;
            java.util.Optional<com.aresstack.spaceharvester.app.runtime.program.StoredProgramSearch.Fact> selected;
            dialogOpen = true;
            try {
                selected = new StoredProgramSearchDialog(snapshot, sources, initialSource).show(parent);
            } finally {
                dialogOpen = false;
            }
            selected.ifPresent(fact -> {
                if (context.runtime() != runtime || request != generation || !active.getAsBoolean()) return;
                Background.run(() -> runtime.workspace().programFactCurrent(fact), valid -> {
                    if (context.runtime() != runtime || request != generation || !active.getAsBoolean()) return;
                    if (!valid) {
                        Dialogs.view(parent, "Programmsuche", Comic.note("Fundstelle nicht mehr aktuell oder lesbar."));
                        return;
                    }
                    var location = fact.location();
                    context.navigator().openProgram(location.key().sourceId(), location.key().pageId(),
                            location.rawSha256(), location.line());
                }, error -> {
                    if (context.runtime() == runtime && request == generation && active.getAsBoolean()) {
                        Dialogs.view(parent, "Programmsuche", Comic.note("Fundstelle nicht verfügbar."));
                    }
                });
            });
        }, error -> {
            if (request != generation) return;
            task = null;
            busy.accept(false);
            if (context.runtime() == runtime && active.getAsBoolean()
                    && !(error instanceof java.util.concurrent.CancellationException)) {
                Dialogs.view(parent, "Programmsuche", Comic.note("Suche fehlgeschlagen."));
            }
        });
    }

 }
