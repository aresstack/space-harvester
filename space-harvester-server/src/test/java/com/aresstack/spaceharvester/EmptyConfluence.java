package com.aresstack.spaceharvester;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Eine erreichbare Confluence ohne Seiten, für Tests, deren Seiten nur im Cache liegen. Seit Seiten bei einem
 * Cache-Fehlschlag nachgeladen werden (ADR 0010), ist „nicht im Cache“ erst dann eine fehlende Seite, wenn die Quelle
 * sie auch nicht kennt; eine nicht erreichbare Quelle wäre dagegen 503.
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
}
