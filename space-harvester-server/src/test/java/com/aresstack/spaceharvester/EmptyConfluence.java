package com.aresstack.spaceharvester;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import java.io.IOException;
import java.time.Clock;
import java.io.UncheckedIOException;

/**
 * Eine erreichbare Confluence ohne Seiten, für Tests, deren Seiten nur im Cache liegen. Seit jeder Seitenabruf Recht und
 * Version live prüft (ADR 0010, Punkt 8), kennt diese Quelle die Seiten der Tests nicht; Tests, die die
 * Cache-Darstellung prüfen (Erweiterte Ansicht, Confluence-kompatible API), ersetzen die Prüfung deshalb durch
 * {@link #cacheOnly}: ohne angebundenen Inhaltsabruf liefert {@code PageQueries} aus dem Cache. Die Live-Prüfung selbst
 * decken {@code PageQueriesTest} und {@code HarvesterTest} ab.
 */
public final class EmptyConfluence {

    private static FakeConfluenceServer server;

    private EmptyConfluence() {
    }

    /** Basis-URL der einen Instanz für alle Tests dieser JVM. */
    public static synchronized String baseUrl() {
        if (server == null) {
            FakeConfluence confluence = new FakeConfluence();
            confluence.space("DEV", "Entwicklung");
            try {
                server = new FakeConfluenceServer(confluence);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        }
        return server.baseUrl().toString();
    }

    /** Seitenprüfung ohne Inhaltsabruf: Seiten kommen aus dem Cache, {@code refresh=true} ergibt 501. */
    public static PageFreshness cacheOnly(SourceQueries queries) {
        return new PageFreshness(queries, null, Clock.systemUTC());
    }
}
