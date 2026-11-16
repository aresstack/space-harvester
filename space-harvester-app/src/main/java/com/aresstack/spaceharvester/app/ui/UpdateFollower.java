package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.application.changelog.ChangeLogListener;
import com.aresstack.spaceharvester.application.changelog.ChangeUpdate;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.sources.HarvestSource;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * „Nach dem Update Änderungen anzeigen“ (ADR 0030, Abschnitt 6): Ist die Einstellung an, öffnet eine neue Update-Marke
 * die Updates ihrer Quelle. Hört auf das Änderungsprotokoll aller Quellen (ADR 0027), fragt nie in Abständen nach und
 * fasst nie von selbst zusammen. Marken von vor dem Anmelden und die Erstbefüllung öffnen nichts.
 */
final class UpdateFollower {

    /** Das Änderungsprotokoll einer Laufzeit; in Tests ersetzbar. */
    interface Feed {

        Subscription subscribe(ChangeLogListener listener);

        List<ChangeUpdate> updates(String sourceId);

        String title(String sourceId);

        static Feed of(Workspace workspace) {
            return new Feed() {
                @Override
                public Subscription subscribe(ChangeLogListener listener) {
                    return workspace.subscribeChangeLog(listener);
                }

                @Override
                public List<ChangeUpdate> updates(String sourceId) {
                    return workspace.changeUpdates(sourceId);
                }

                @Override
                public String title(String sourceId) {
                    return workspace.sources().stream().filter(source -> source.id().equals(sourceId))
                            .map(HarvestSource::title).findFirst().orElse(sourceId);
                }
            };
        }
    }

    /** Führt Arbeit im Hintergrund aus und meldet auf dem EDT zurück (Tests: synchron). */
    interface Runner {
        <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    private final BooleanSupplier enabled;
    private final BiConsumer<String, String> open;
    private final Runner runner;
    private final Supplier<Instant> clock;
    /** Je Quelle die jüngste Marke, die schon berücksichtigt ist. */
    private final Map<String, Instant> seen = new ConcurrentHashMap<>();
    private volatile Feed feed;
    private volatile Instant since = Instant.MIN;
    private Subscription subscription = Subscription.NONE;

    UpdateFollower(BooleanSupplier enabled, BiConsumer<String, String> open, Runner runner, Supplier<Instant> clock) {
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.open = Objects.requireNonNull(open, "open");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Folgt den Updates der jeweils laufenden App: meldet sich bei jedem Wechsel der Laufzeit (Start, neu geladene
     * Konfiguration) an der neuen an.
     */
    static UpdateFollower install(AppContext context, BiConsumer<String, String> open) {
        UpdateFollower follower = new UpdateFollower(() -> showAfterUpdate(context), open, Background::run,
                Instant::now);
        AppRuntime[] known = new AppRuntime[1];
        Consumer<AppContext> attach = ignored -> {
            AppRuntime runtime = context.runtime();
            if (runtime != known[0]) {
                known[0] = runtime;
                follower.attach(runtime == null ? null : Feed.of(runtime.workspace()));
            }
        };
        context.onChange(attach);
        attach.accept(context);
        return follower;
    }

    private static boolean showAfterUpdate(AppContext context) {
        // TODO CL6: Einstellung aus dem Core von Strang Q (space-harvester.cache.change-log), Standard aus.
        return false;
    }

    /** Meldet sich am Protokoll {@code next} an, {@code null} für keines; eine vorige Anmeldung endet. */
    void attach(Feed next) {
        subscription.close();
        subscription = Subscription.NONE;
        seen.clear();
        feed = next;
        since = clock.get();
        if (next == null) {
            return;
        }
        try {
            subscription = next.subscribe(this::changed);
        } catch (RuntimeException e) {
            subscription = Subscription.NONE;
        }
    }

    /** Neue Zeilen oder eine neue Marke in {@code sourceId}; kommt auf dem Zustell-Thread des Core. */
    void changed(String sourceId) {
        Feed current = feed;
        if (current == null || sourceId == null || !enabled.getAsBoolean()) {
            return;
        }
        runner.run(() -> {
            Instant newest = newest(current.updates(sourceId));
            return newest == null ? null : new Found(newest, current.title(sourceId));
        }, found -> {
            if (found == null || current != feed) {
                return;
            }
            Instant before = seen.getOrDefault(sourceId, since);
            if (!found.mark().isAfter(before)) {
                return;
            }
            seen.put(sourceId, found.mark());
            String title = found.title();
            if (SwingUtilities.isEventDispatchThread()) {
                open.accept(sourceId, title);
            } else {
                SwingUtilities.invokeLater(() -> open.accept(sourceId, title));
            }
        }, failure -> { });
    }

    private record Found(Instant mark, String title) {
    }

    /** Zeitpunkt der jüngsten abgeschlossenen Marke, die keine Erstbefüllung ist; {@code null} ohne. */
    private static Instant newest(List<ChangeUpdate> updates) {
        return updates.stream().filter(update -> !update.open() && !update.initial() && update.mark() != null)
                .map(update -> update.mark().at()).max(Instant::compareTo).orElse(null);
    }

    // ---- für Tests

    boolean attached() {
        return subscription != Subscription.NONE;
    }
}
