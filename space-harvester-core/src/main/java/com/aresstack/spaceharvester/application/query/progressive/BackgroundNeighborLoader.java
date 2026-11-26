package com.aresstack.spaceharvester.application.query.progressive;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.PageLoads;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Lädt fehlende Nachbarn als Hintergrundauftrag ({@link PageLoads}, Angelo 21.11.2026): sichtbar in der
 * Auftragsliste, nachrangig, je Space ein Unterauftrag, nach einem Neustart fortgesetzt. Schon gespeicherte Seiten
 * lässt {@link PageLoads} aus. Delegierte Benutzer (Dienst mit Anmeldung) laden nie im Hintergrund: ihre Anmeldung soll
 * die Anfrage nicht überdauern; für sie gibt es kein Nachladen.
 */
public final class BackgroundNeighborLoader implements NeighborLoader {

    /** Titel der Gruppe in der Auftragsliste. */
    public static final String TITLE = "Nachbarseiten laden";

    private final PageLoads loads;
    private final BackgroundJobs background;
    private final java.util.function.Predicate<String> acquisitionAllowed;

    public BackgroundNeighborLoader(PageLoads loads, BackgroundJobs background) {
        this(loads, background, sourceId -> true);
    }

    public BackgroundNeighborLoader(PageLoads loads, BackgroundJobs background,
                                    java.util.function.Predicate<String> acquisitionAllowed) {
        this.acquisitionAllowed = Objects.requireNonNull(acquisitionAllowed, "acquisitionAllowed");
        this.loads = Objects.requireNonNull(loads, "loads");
        this.background = Objects.requireNonNull(background, "background");
    }

    @Override
    public Optional<Load> load(AccessContext caller, Collection<PageKey> pages) {
        if (caller.credential() != null || pages.isEmpty()) {
            return Optional.empty();
        }
        List<PageKey> eligible = pages.stream().filter(key -> acquisitionAllowed.test(key.sourceId())).toList();
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        PageLoads.Joined joined = loads.join(caller, TITLE, eligible, false,
                com.aresstack.spaceharvester.application.changelog.ChangeTrigger.SEARCH);
        if (joined.groups().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new GroupsLoad(joined, eligible.size()));
    }

    /**
     * Das Laden über alle beteiligten Gruppen: die eigene, neue und fremde, die übersprungene Seiten schon laden.
     * Zu Ende ist es, wenn in keiner Gruppe mehr ein Unterauftrag wartet, läuft oder angehalten ist.
     */
    private final class GroupsLoad implements Load {

        private final String started;
        private final List<String> groups;
        private final int pages;

        GroupsLoad(PageLoads.Joined joined, int pages) {
            this.started = joined.started().map(BackgroundGroup::groupId).orElse(null);
            this.groups = List.copyOf(joined.groups());
            this.pages = pages;
        }

        @Override
        public String id() {
            return started != null ? started : groups.get(0);
        }

        @Override
        public int pages() {
            return pages;
        }

        @Override
        public Subscription whenDone(Consumer<Boolean> done) {
            Objects.requireNonNull(done, "done");
            AtomicBoolean told = new AtomicBoolean();
            List<Subscription> watching = new CopyOnWriteArrayList<>();
            Runnable check = () -> {
                List<Optional<BackgroundGroup>> now = groups.stream().map(background::group).toList();
                boolean open = now.stream().flatMap(Optional::stream).flatMap(group -> group.parts().stream())
                        .anyMatch(part -> PageLoads.loading(part.state()));
                if (!open && told.compareAndSet(false, true)) {
                    watching.forEach(Subscription::close);
                    boolean cancelled = now.stream().flatMap(Optional::stream).flatMap(group -> group.parts().stream())
                            .allMatch(part -> part.state() == JobState.CANCELLED)
                            && now.stream().anyMatch(Optional::isPresent);
                    done.accept(cancelled);
                }
            };
            for (String group : groups) {
                watching.add(background.watch(group, ignored -> check.run()));
            }
            // Schon fertig, bevor jemand zusah: sofort melden.
            check.run();
            if (told.get()) {
                watching.forEach(Subscription::close);
            }
            return () -> watching.forEach(Subscription::close);
        }

        @Override
        public void cancel() {
            // Nur die eigene Gruppe; fremde gehören anderen Suchen.
            if (started != null) {
                background.cancel(started);
            }
        }
    }
}
