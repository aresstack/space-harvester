package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.app.runtime.FollowUpJobs;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.passages.PagePassages;
import com.aresstack.spaceharvester.application.passages.PassageId;
import com.aresstack.spaceharvester.application.passages.PassageLookup;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprung per Passage-ID (#452) mit echter Laufzeit: Die ID eines Absatzes aus der Antwort des Core öffnet seine Seite
 * im Reiter „Absätze“, der Absatz ist gewählt und im Lesetext markiert. Nach einer Änderung der Seite öffnet dieselbe ID
 * die Seite mit dem Hinweis auf die ältere Fassung; eine unbekannte Seite meldet sich wie ein Ladefehler.
 */
class ReaderPassageLinkTest {

    private static final String PAGE = "<h1>Einleitung</h1><p>Das Handbuch beschreibt den Betrieb des Dienstes.</p>"
            + "<h2>Betrieb</h2><p>Der Dienst startet jeden Morgen und gleicht alle Spaces ab.</p>"
            + "<h2>Sicherheit</h2><p>Zugänge vergibt nur die Fachabteilung.</p>";
    private static final String CHANGED = "<h1>Einleitung</h1><p>Das Handbuch ist neu geschrieben.</p>"
            + "<h2>Betrieb</h2><p>Der Dienst startet nur noch auf Anforderung.</p>";

    @TempDir
    Path home;

    private HttpServer server;
    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aPassageIdOpensItsPageOnThePassagesTabWithThePassageMarked() throws Exception {
        FakeConfluence wiki = new FakeConfluence();
        wiki.space("DEV", "Entwicklung");
        FakeConfluence.FakePage page = wiki.page("300", "Handbuch", "DEV", "<p>Ansicht</p>").storage(PAGE);
        wiki.homepage("DEV", "300");
        Workspace workspace = start(serve(wiki));
        sync(workspace);

        ContentView view = onEdt(() -> new ContentView(context));
        PageDetailPanel panel = view.detail();
        // Die ID stammt aus der Absatz-Antwort des Core; ohne Embedding-Modell nach Struktur, auch dann mit ID.
        PagePassages passages = passages(panel);
        assertThat(passages.passages()).hasSizeGreaterThanOrEqualTo(3);
        PagePassages.Passage target = passages.passages().get(1);
        assertThat(PassageId.parse(target.id()).pageId()).isEqualTo("300");
        assertThat(workspace.passage(target.id()).match()).isEqualTo(PassageLookup.Match.FOUND);

        onEdt(() -> {
            panel.tabs().select("Text");
            panel.reader().setCaretPosition(0);
            view.openPassage("wiki", target.id());
            return null;
        });
        awaitTrue(() -> onEdt(() -> panel.passages().selectedIndex() == 1));
        assertThat(onEdt(() -> panel.tabs().selected())).isEqualTo("Absätze");
        assertThat(onEdt(() -> panel.reader().getSelectionStart())).isEqualTo(target.start());
        assertThat(onEdt(() -> panel.reader().getSelectionEnd())).isEqualTo(target.end());
        assertThat(onEdt(() -> panel.feedback().isVisible())).isFalse();
        assertThat(onEdt(view::chosenSource)).isEqualTo("wiki");

        // FOUND, aber der Reiter kennt den Absatz nicht (anderes Ergebnis als die Auflösung): Hinweis statt Stille.
        PassageLookup found = workspace.passage(target.id());
        String absent = PassageId.of("wiki", "300", "0".repeat(PassageId.CAPTURE_LENGTH), 99, "aaaaaaaa",
                "bbbbbbbb").value();
        onEdt(() -> {
            panel.showPassage(new PassageLookup(PassageId.parse(absent), PassageLookup.Match.FOUND, found.page(),
                    found.passages()));
            return null;
        });
        assertThat(onEdt(() -> panel.feedback().isVisible())).isTrue();
        assertThat(onEdt(() -> panel.feedback().text())).startsWith(PageDetailPanel.PASSAGE_MISSING);

        // Neue Fassung der Seite: dieselbe ID öffnet die Seite trotzdem, mit Hinweis und ohne gewählten Absatz.
        synchronized (wiki) {
            page.storage(CHANGED).version(2, "2026-10-19T08:00:00.000Z");
        }
        sync(workspace);
        assertThat(workspace.passage(target.id()).match()).isEqualTo(PassageLookup.Match.OLDER_VERSION);
        onEdt(() -> {
            panel.tabs().select("Text");
            view.openPassage("wiki", target.id());
            return null;
        });
        awaitTrue(() -> onEdt(() -> panel.shownPage() != null && panel.feedback().isVisible()));
        assertThat(onEdt(() -> panel.feedback().text())).startsWith(PageDetailPanel.PASSAGE_OLDER);
        assertThat(onEdt(() -> panel.tabs().selected())).isEqualTo("Absätze");
        assertThat(onEdt(() -> panel.passages().selectedIndex())).isEqualTo(-1);
        assertThat(onEdt(() -> panel.passages().titles())).isNotEmpty();

        // Unbekannte Seite: Meldung wie bei jedem anderen Ladefehler, keine Seite.
        String unknown = PassageId.of("wiki", "999", "0".repeat(PassageId.CAPTURE_LENGTH), 0, "aaaaaaaa",
                "bbbbbbbb").value();
        onEdt(() -> {
            view.openPassage("wiki", unknown);
            return null;
        });
        awaitTrue(() -> onEdt(() -> !panel.feedback().text().startsWith(PageDetailPanel.PASSAGE_OLDER)
                && !panel.feedback().text().startsWith("Seite wird geladen")));
        assertThat(onEdt(() -> panel.feedback().isVisible())).isTrue();
        assertThat(onEdt(() -> panel.feedback().text())).contains("Nicht im Bestand");
        assertThat(onEdt(panel::shownPage)).isNull();
    }

    /** Öffnet die Seite, bis ihre Absätze aus der Anreicherung vorliegen. */
    private PagePassages passages(PageDetailPanel panel) throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            onEdt(() -> {
                panel.load("wiki", "300", false);
                return null;
            });
            awaitTrue(() -> onEdt(panel::readerDocument) != null);
            PagePassages passages = onEdt(panel::readerDocument).passages();
            if (passages.state() == PagePassages.State.CURRENT && !passages.passages().isEmpty()) {
                return passages;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Keine Absätze");
    }

    private void sync(Workspace workspace) throws Exception {
        String job = workspace.startSync("wiki", "DEV").id();
        for (int i = 0; i < 600 && !workspace.job(job).state().terminal(); i++) {
            Thread.sleep(50);
        }
        assertThat(workspace.job(job).state()).isEqualTo(JobState.COMPLETED);
        FollowUpJobs.awaitEnrichments(workspace);
    }

    private Workspace start(URI wiki) throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: wiki
                      type: confluence
                      title: Wiki
                      base-url: %s
                """.formatted(home.resolve("data"), wiki));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(context.runtime()).isNotNull();
        return context.runtime().workspace();
    }

    /** Liefert eine {@link FakeConfluence} über HTTP auf 127.0.0.1 aus. */
    private URI serve(FakeConfluence confluence) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> answer(confluence, exchange));
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + FakeConfluence.BASE.getPath());
    }

    private static void answer(FakeConfluence confluence, HttpExchange exchange) throws IOException {
        URI incoming = exchange.getRequestURI();
        String query = incoming.getRawQuery();
        URI mapped = URI.create(FakeConfluence.BASE.getScheme() + "://" + FakeConfluence.BASE.getHost()
                + incoming.getRawPath() + (query == null ? "" : "?" + query));
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        ConfluenceHttpResponse response;
        synchronized (confluence) {
            response = confluence.get(mapped, headers, Integer.MAX_VALUE);
        }
        byte[] body = response.body();
        if (response.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", response.contentType());
        }
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void awaitTrue(ThrowingSupplier<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.get()) {
            assertThat(System.nanoTime()).as("Zeit abgelaufen").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private static <T> T onEdt(ThrowingSupplier<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.get());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
