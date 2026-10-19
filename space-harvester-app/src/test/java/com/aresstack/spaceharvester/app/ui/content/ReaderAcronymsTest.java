package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.runtime.FollowUpJobs;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Relation;
import com.aresstack.spaceharvester.application.enrichment.PageAcronyms;
import com.aresstack.spaceharvester.application.images.PageImages;
import com.aresstack.spaceharvester.application.passages.PagePassages;
import com.aresstack.spaceharvester.application.terms.PageTerms;
import com.aresstack.spaceharvester.application.terms.TermKind;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ende zu Ende ohne Bildschirm (#115): zwei Fake-Confluences, Abgleich über die echte Laufzeit der App, dann zeigt der
 * Reiter „Inhalt“ die Akronyme aus der Core-Analyse {@code acronyms}: jede Fundstelle markiert, bekannte und unbekannte
 * Ausschreibungen, getrennt nach Quelle, nichts auf einer neueren Live-Fassung, „Analyse neu berechnen“ aktualisiert.
 */
class ReaderAcronymsTest {

    private static final String WIKI_PAGE = "<h1>Schnittstellen</h1><p>Die Application Programming Interface (API) "
            + "nutzt REST.</p><p>Jede API und alle APIs melden sich per SSO (Single Sign-On) an. RAPID und xAPI "
            + "zählen nicht als API-Treffer. Grüße an die ÜBV.</p>";
    private static final String OTHER_PAGE = "<h1>Prüfung</h1><p>Die API (Automatische Prüfungs Instanz) prüft "
            + "jede Lieferung.</p>";

    @TempDir
    Path home;

    private final List<HttpServer> servers = new ArrayList<>();
    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void theReaderMarksTheCoreAcronymsOfTheShownPageOnly() throws Exception {
        FakeConfluence wiki = confluence("200", "Schnittstellen", WIKI_PAGE);
        FakeConfluence other = confluence("200", "Prüfung", OTHER_PAGE);
        Workspace workspace = start(serve(wiki), serve(other));
        sync(workspace, "wiki");
        sync(workspace, "zweites");

        PageDetailPanel panel = onEdt(() -> new PageDetailPanel(context));
        ReaderDocument shown = open(panel, "wiki", "200", false);

        assertThat(shown.acronyms().state()).isEqualTo(PageAcronyms.State.CURRENT);
        assertThat(shown.acronyms().result().processorId()).isEqualTo("acronyms");
        assertThat(shown.acronyms().result().processorVersion()).isEqualTo("4;sentences=opennlp-de");
        assertThat(shown.acronyms().result().key().sourceId()).isEqualTo("wiki");
        assertThat(onEdt(panel::acronymHint)).contains("Core-Analyse „acronyms“ Version 4")
                .contains("Fundstellen markiert");
        String text = onEdt(() -> panel.reader().getDocument().getText(0, panel.reader().getDocument().getLength()));
        assertThat(text).isEqualTo(shown.text().text());
        // Dokumentnavigation des Core (#396) als Wolkenbanner über dem Lesetext; ohne Modell nur die Überschriften.
        Navigation navigation = onEdt(() -> panel.navigationSky().navigation());
        assertThat(navigation).isNotNull();
        assertThat(navigation.analysis()).isEqualTo(Navigation.Analysis.HEADINGS);
        assertThat(navigation.chapters()).extracting(Navigation.Section::label).containsExactly("Schnittstellen");
        Navigation.Section chapter = navigation.chapters().get(0);
        assertThat(text.substring(chapter.start())).startsWith("Schnittstellen");

        List<String> marked = shown.marks().stream()
                .map(mark -> text.substring(mark.occurrence().start(), mark.occurrence().end())).toList();
        assertThat(marked).containsExactly("API", "REST", "API", "APIs", "SSO", "RAPID", "xAPI", "API", "ÜBV");
        assertThat(acronym(shown, "API").count()).isEqualTo(4);
        assertThat(acronym(shown, "API").expansion()).isEqualTo("Application Programming Interface");
        assertThat(acronym(shown, "SSO").expansion()).isEqualTo("Single Sign-On");
        assertThat(ReaderDocument.tooltip(acronym(shown, "REST")))
                .isEqualTo("REST: Ausschreibung unbekannt (steht nicht auf dieser Seite)");
        assertThat(ReaderDocument.tooltip(acronym(shown, "API"))).doesNotStartWith("<html>");

        StyledDocument document = shown.document();
        for (PageAcronyms.Mark mark : shown.marks()) {
            for (int offset = mark.occurrence().start(); offset < mark.occurrence().end(); offset++) {
                assertThat(StyleConstants.isUnderline(document.getCharacterElement(offset).getAttributes()))
                        .as("markiert bei %d", offset).isTrue();
            }
        }
        // Gemischte Schreibung wie im EAC: xAPI ist ein eigenes Akronym, kein API-Treffer.
        int xApi = text.indexOf("xAPI") + 1;
        assertThat(shown.markAt(xApi).acronym().acronym()).isEqualTo("xAPI");
        int heading = text.indexOf("Schnittstellen");
        assertThat(StyleConstants.isBold(document.getCharacterElement(heading).getAttributes())).isTrue();

        // Tastatur: Alt+→ springt zur ersten Fundstelle und zeigt die Ausschreibung
        onEdt(() -> {
            panel.reader().setCaretPosition(0);
            panel.jumpToAcronym(true);
            return null;
        });
        assertThat(onEdt(() -> panel.reader().getSelectedText())).isEqualTo("API");
        assertThat(onEdt(panel::acronymDetail)).isEqualTo("API: Application Programming Interface · 4× auf dieser "
                + "Seite · zuerst in „Schnittstellen“");
        onEdt(() -> {
            panel.reader().setCaretPosition(text.indexOf("REST") + 1);
            return null;
        });
        assertThat(onEdt(panel::acronymDetail)).startsWith("REST: Ausschreibung unbekannt");

        // Reiter „Begriffe“ (#339): dieselben Akronyme; ein Klick springt zur nächsten Fundstelle im Lesetext.
        assertThat(onEdt(() -> panel.terms().stateLabel())).isEqualTo("Aktuell");
        assertThat(onEdt(() -> panel.terms().counts().get(0)))
                .isEqualTo(String.valueOf(shown.acronyms().acronyms().size()));
        onEdt(() -> {
            panel.reader().setCaretPosition(text.indexOf("SSO") + 3);
            panel.jumpTo(acronym(shown, "API"));
            return null;
        });
        assertThat(onEdt(() -> panel.reader().getSelectionStart())).isGreaterThan(text.indexOf("SSO"));
        assertThat(onEdt(() -> panel.reader().getSelectedText())).isEqualTo("API");
        // Bezeichner aus dem Begriffsindex des Core (#119), für genau diesen Text
        assertThat(shown.terms().state()).isEqualTo(PageTerms.State.CURRENT);
        assertThat(onEdt(() -> panel.terms().counts().get(1)))
                .isEqualTo(String.valueOf(shown.terms().identifierCount()));
        assertThat(onEdt(() -> panel.terms().identifierChips().size())).isEqualTo(shown.terms().identifierCount());
        // Das globale Verzeichnis (Bereich „Begriffe“) liest denselben Index.
        TermQueries.TermList api = workspace.terms(new TermQueries.TermSearch(null, null, TermKind.ACRONYM, "API",
                true, 10, null));
        assertThat(api.terms()).singleElement().satisfies(term -> {
            assertThat(term.expansions()).contains("Application Programming Interface");
        });

        // Absätze (#354): ohne Embedding-Modell die strukturelle Rückfallebene des Core, Klick markiert den Absatz
        assertThat(shown.passages().state()).isEqualTo(PagePassages.State.CURRENT);
        assertThat(onEdt(() -> panel.passages().titles())).hasSize(shown.passages().passages().size()).isNotEmpty();
        assertThat(onEdt(() -> panel.passages().mode())).isEqualTo(PassagesTab.STRUCTURAL);
        PagePassages.Passage first = shown.passages().passages().get(0);
        onEdt(() -> {
            panel.reader().setCaretPosition(0);
            panel.passages().click(0);
            return null;
        });
        assertThat(onEdt(() -> panel.reader().getSelectionStart())).isEqualTo(first.start());
        assertThat(onEdt(() -> panel.reader().getSelectionEnd())).isEqualTo(first.end());

        // Reiter „Bilder“ (#362): ohne Vision-Modell nennt er den Grund des Core und bietet kein Beschreiben an.
        onEdt(() -> {
            panel.tabs().select("Bilder");
            return null;
        });
        awaitTrue(() -> onEdt(() -> panel.images().hint()).equals(ImagesTab.NO_MODEL));
        assertThat(onEdt(() -> panel.images().describeOffered())).isFalse();
        assertThat(onEdt(() -> panel.images().stateLabel())).isEqualTo("Nicht durchgeführt");
        assertThat(context.runtime().workspace().images(context.runtime().workspace().page("wiki", "200", false))
                .reason()).isEqualTo(PageImages.VISION_MODEL_MISSING);
        onEdt(() -> {
            panel.tabs().select("Text");
            return null;
        });

        // Beziehungen (#120): Vorschläge der Akronym-Regel stehen im Reiter „Begriffe“ genau dieser Seite.
        assertThat(workspace.proposeAcronymRelations("wiki").added()).isPositive();
        ReaderDocument related = open(panel, "wiki", "200", false);
        assertThat(related.relations()).extracting(Relation::line)
                .contains("API · Abkürzung für · Application Programming Interface (Vorgeschlagen, Regel)");
        assertThat(onEdt(() -> panel.terms().counts().get(2))).isEqualTo(String.valueOf(related.relations().size()));

        // Gleiche Seiten-ID, gleiches Akronym, andere Quelle: eigene Ausschreibung, nichts aus „wiki“
        ReaderDocument foreign = open(panel, "zweites", "200", false);
        assertThat(foreign.acronyms().result().key().sourceId()).isEqualTo("zweites");
        assertThat(foreign.acronyms().acronyms()).extracting(PageAcronyms.Acronym::acronym).containsExactly("API");
        assertThat(acronym(foreign, "API").expansion()).isEqualTo("Automatische Prüfungs Instanz");
        assertThat(onEdt(panel::acronymDetail)).isEqualTo(" ");
        assertThat(acronym(open(panel, "wiki", "200", false), "API").expansion())
                .isEqualTo("Application Programming Interface");

        // Neuberechnung ersetzt den Lesetext durch einen neu gebauten mit denselben Fundstellen
        ReaderDocument before = onEdt(panel::readerDocument);
        onEdt(() -> {
            panel.recompute();
            return null;
        });
        ReaderDocument recomputed = await(panel, next -> next != null && next != before);
        assertThat(recomputed.acronyms().state()).isEqualTo(PageAcronyms.State.CURRENT);
        assertThat(recomputed.marks()).hasSameSizeAs(before.marks());

        // Neue Fassung in der Quelle: die Prüfung legt sie im Cache ab und analysiert sie sofort (ADR 0010)
        wiki.page("200", "Schnittstellen", "DEV", "<p>neu</p>")
                .storage("<p>Jetzt nur noch CI (Continuous Integration) und eine API.</p>")
                .version(4, "2026-10-17T10:00:00.000+02:00");
        ReaderDocument live = open(panel, "wiki", "200", true);
        assertThat(live.text().text()).contains("Continuous Integration");
        assertThat(live.acronyms().state()).isEqualTo(PageAcronyms.State.CURRENT);
        assertThat(live.acronyms().acronyms()).extracting(PageAcronyms.Acronym::acronym)
                .containsExactly("API", "CI");

        // Nach dem nächsten Abgleich gehört die Analyse zur neuen Fassung
        sync(workspace, "wiki");
        ReaderDocument synced = open(panel, "wiki", "200", false);
        assertThat(synced.acronyms().state()).isEqualTo(PageAcronyms.State.CURRENT);
        assertThat(synced.acronyms().acronyms()).extracting(PageAcronyms.Acronym::acronym)
                .containsExactly("API", "CI");
        assertThat(acronym(synced, "CI").expansion()).isEqualTo("Continuous Integration");
    }

    @Test
    void aPageMissingFromTheCacheIsLoadedStoredAndAnalysed() throws Exception {
        FakeConfluence wiki = confluence("200", "Schnittstellen", WIKI_PAGE);
        Workspace workspace = start(serve(wiki), serve(confluence("300", "Leer", "<p>leer</p>")));
        sync(workspace, "wiki");
        wiki.page("201", "Neu", "DEV", "<p>neu</p>").storage("<p>Die API (Application Programming Interface).</p>");

        PageDetailPanel panel = onEdt(() -> new PageDetailPanel(context));
        ReaderDocument shown = open(panel, "wiki", "201", true);

        assertThat(shown.acronyms().state()).isEqualTo(PageAcronyms.State.CURRENT);
        assertThat(shown.acronyms().acronyms()).extracting(PageAcronyms.Acronym::acronym).containsExactly("API");
        assertThat(shown.marks()).isNotEmpty();
    }

    private static PageAcronyms.Acronym acronym(ReaderDocument document, String name) {
        return document.acronyms().acronyms().stream().filter(a -> a.acronym().equals(name)).findFirst()
                .orElseThrow();
    }

    /**
     * Öffnet die Seite und wartet auf ihre fertige Akronym-Analyse: Nach einem Abgleich läuft die Anreicherung als
     * Folgeauftrag; bis sie fertig ist, zeigt die Seite noch keine Fundstellen. Dann wird die Seite erneut geöffnet.
     */
    private ReaderDocument open(PageDetailPanel panel, String sourceId, String pageId, boolean refresh)
            throws Exception {
        ReaderDocument shown = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            onEdt(() -> {
                panel.load(sourceId, pageId, refresh);
                return null;
            });
            shown = await(panel, document -> document != null);
            if (shown.acronyms().state() == PageAcronyms.State.CURRENT
                    && shown.acronyms().result().key().sourceId().equals(sourceId)) {
                return shown;
            }
            Thread.sleep(250);
        }
        return shown;
    }

    private static ReaderDocument await(PageDetailPanel panel, Predicate<ReaderDocument> ready) throws Exception {
        for (int i = 0; i < 600; i++) {
            ReaderDocument document = onEdt(panel::readerDocument);
            if (ready.test(document)) {
                return document;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Lesetext nicht angezeigt");
    }

    private void sync(Workspace workspace, String sourceId) throws Exception {
        String job = workspace.startSync(sourceId, "DEV").id();
        for (int i = 0; i < 600 && !workspace.job(job).state().terminal(); i++) {
            Thread.sleep(50);
        }
        assertThat(workspace.job(job).state()).isEqualTo(JobState.COMPLETED);
        // Akronyme rechnet die Anreicherung zuerst, Begriffe zuletzt: auf den ganzen Folgeauftrag warten.
        FollowUpJobs.awaitEnrichments(workspace);
    }

    private static FakeConfluence confluence(String pageId, String title, String storage) {
        FakeConfluence confluence = new FakeConfluence();
        confluence.space("DEV", "Entwicklung");
        confluence.page(pageId, title, "DEV", "<p>Ansicht</p>").storage(storage);
        confluence.homepage("DEV", pageId);
        return confluence;
    }

    private Workspace start(URI wiki, URI other) throws Exception {
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
                    - id: zweites
                      type: confluence
                      title: Zweites Wiki
                      base-url: %s
                """.formatted(home.resolve("data"), wiki, other));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(context.runtime()).isNotNull();
        return context.runtime().workspace();
    }

    /** Liefert eine {@link FakeConfluence} über HTTP auf 127.0.0.1 aus. */
    private URI serve(FakeConfluence confluence) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> answer(confluence, exchange));
        server.start();
        servers.add(server);
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
