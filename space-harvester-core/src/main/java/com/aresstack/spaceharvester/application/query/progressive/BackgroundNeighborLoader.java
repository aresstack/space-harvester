package com.aresstack.spaceharvester.application.query.progressive;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.PageLoads;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Collection;
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

    public BackgroundNeighborLoader(PageLoads loads, BackgroundJobs background) {
        this.loads = Objects.requireNonNull(loads, "loads");
        this.background = Objects.requireNonNull(background, "background");
    }

    @Override
    public Optional<Load> load(AccessContext caller, Collection<PageKey> pages) {
        if (caller.credential() != null || pages.isEmpty()) {
            return Optional.empty();
        }
        return loads.load(caller, TITLE, pages).map(group -> new GroupLoad(group, pages.size()));
    }

    private final class GroupLoad implements Load {

        private final String id;
        private final int pages;

        GroupLoad(BackgroundGroup group, int pages) {
            this.id = group.groupId();
            this.pages = pages;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int pages() {
            return pages;
        }

        @Override
        public Subscription whenDone(Consumer<Boolean> done) {
            Objects.requireNonNull(done, "done");
            return background.whenFinished(id, group -> done.accept(group.parts().stream()
                    .anyMatch(part -> part.state() == JobState.CANCELLED)));
        }

        @Override
        public void cancel() {
            background.cancel(id);
        }
    }
}
