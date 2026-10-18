package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.AbstractButton;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die Suche der App ist die Schlagwortsuche des Core über den Cache (ADR 0013): Treffer zeigen Titel, Quelle, Space
 * und Auszug, „Weitere laden“ folgt dem Cursor, und eine Quelle ohne eigene Suche oder ohne Verbindung steht unter
 * der Liste, statt still zu fehlen. Ein Bestand ist nicht nötig.
 */
class SearchViewTest {

    private static final int PAGES_PER_SOURCE = 60;

    @TempDir
    Path home;

    private AppContext context;
    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void allSourcesIsOneGlobalKeywordSearchWithExcerptsAndLoadMore() throws Exception {
        start(false);

        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SearchView(context);
            view[0].search("betrieb");
        });
        JTable table = find(whole(view[0]), JTable.class).get(0);
        AbstractButton more = button(whole(view[0]), "Weitere laden");
        // ADR 0013: Die Quellen finden, die Seiten werden in den Cache geladen, Treffer kommen aus dem Cache. Was beim
        // Antworten noch lädt, kommt über „Weitere laden“.
        await(() -> table.getRowCount() > 0);
        assertThat(view[0].statusText()).contains("Dateien: Volltext im Cache").doesNotContain("Bestand");
        loadAll(table, more, 2 * PAGES_PER_SOURCE);

        Set<String> keys = new HashSet<>();
        Set<String> sourcesSeen = new HashSet<>();
        String[] first = new String[4];
        SwingUtilities.invokeAndWait(() -> {
            for (int column = 0; column < 4; column++) {
                first[column] = String.valueOf(table.getModel().getValueAt(0, column));
            }
            for (int row = 0; row < table.getRowCount(); row++) {
                String sourceId = (String) table.getModel().getValueAt(row, 1);
                sourcesSeen.add(sourceId);
                keys.add(sourceId + "/" + table.getModel().getValueAt(row, 0));
            }
        });
        assertThat(first[0]).startsWith("Betrieb ");
        assertThat(first[2]).isEqualTo("DEV");
        assertThat(keys).hasSize(2 * PAGES_PER_SOURCE);
        assertThat(sourcesSeen).containsExactlyInAnyOrder("alpha", "beta");
        assertThat(view[0].statusText()).doesNotContain("unvollständig");
        await(() -> view[0].progressText.getText().startsWith(SearchView.DONE));
        assertThat(view[0].progressText.getText()).isEqualTo("Die Suche ist abgeschlossen · "
                + (2 * PAGES_PER_SOURCE) + " Treffer");
    }

    @Test
    void loadedPagesMoveUpButNoHitEverDisappears() {
        SearchView.HitsModel model = new SearchView.HitsModel();
        model.set(List.of(hit("a"), hit("b"), hit("c")));
        // c ist geladen und steht beim Core vorn; b fehlt in der neuen Liste und bleibt trotzdem.
        model.reorder(0, List.of(hit("c"), hit("a")));
        assertThat(pageIds(model)).containsExactly("c", "a", "b");
        // Frühere Stapel bleiben oben; ein Treffer aus ihnen wird nur aktualisiert.
        model.reorder(1, List.of(hit("b"), new SearchView.Hit("s", "c", "neu", "DEV", "", null)));
        assertThat(pageIds(model)).containsExactly("c", "b", "a");
        assertThat(model.getValueAt(0, 0)).isEqualTo("neu");
    }

    private static SearchView.Hit hit(String pageId) {
        return new SearchView.Hit("s", pageId, "Seite " + pageId, "DEV", "", null);
    }

    private static List<String> pageIds(SearchView.HitsModel model) {
        List<String> ids = new ArrayList<>();
        for (int row = 0; row < model.getRowCount(); row++) {
            ids.add(model.rows.get(row).pageId());
        }
        return ids;
    }

    @Test
    void waitingForPagesNeverFetchesTheNextBatchOnItsOwn() throws Exception {
        start(false);

        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SearchView(context);
            view[0].search("betrieb");
        });
        JTable table = find(whole(view[0]), JTable.class).get(0);
        AbstractButton more = button(whole(view[0]), "Weitere laden");
        await(() -> table.getRowCount() > 0 && more.isEnabled());
        // Das Nachfragen wiederholt nur den angezeigten Stapel; weitere Treffer holt erst „Weitere laden“.
        Thread.sleep(3 * 1000);
        int[] rows = new int[1];
        SwingUtilities.invokeAndWait(() -> rows[0] = table.getRowCount());
        assertThat(rows[0]).isLessThanOrEqualTo(2 * SearchView.LIMIT);
        assertThat(more.isEnabled()).isTrue();
    }

    private final java.util.Map<SearchView, javax.swing.JPanel> wholes = new java.util.IdentityHashMap<>();

    /** Arbeitsfläche und Aktionszeile zusammen, wie im Hauptfenster. */
    private javax.swing.JPanel whole(SearchView view) {
        return wholes.computeIfAbsent(view, v -> {
            javax.swing.JPanel[] panel = new javax.swing.JPanel[1];
            Runnable build = () -> {
                panel[0] = new javax.swing.JPanel(new java.awt.BorderLayout());
                panel[0].add(v.component(), java.awt.BorderLayout.CENTER);
                panel[0].add(v.actions(), java.awt.BorderLayout.SOUTH);
            };
            if (SwingUtilities.isEventDispatchThread()) {
                build.run();
            } else {
                try {
                    SwingUtilities.invokeAndWait(build);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return panel[0];
        });
    }

    /** Klickt „Weitere laden“, bis alle erwarteten Treffer da sind und nichts mehr folgt. */
    private static void loadAll(JTable table, AbstractButton more, int expected) throws Exception {
        for (int i = 0; i < 1200; i++) {
            boolean[] state = new boolean[2];
            SwingUtilities.invokeAndWait(() -> {
                state[0] = table.getRowCount() == expected && !more.isEnabled();
                state[1] = more.isEnabled();
                if (state[1]) {
                    more.doClick();
                }
            });
            if (state[0]) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Nach „Weitere laden“ nicht alle Treffer da: " + table.getRowCount() + " Zeilen, "
                + (more.isEnabled() ? "weitere" : "keine weiteren"));
    }

    @Test
    void pagesStillLoadingIntoTheCacheAreCounted() {
        assertThat(SearchView.progressLine(12, 25, 7)).isEqualTo("12 von 25 Seiten im Cache · 7 Treffer");
        assertThat(SearchView.progressLine(0, 1, 1)).isEqualTo("0 von 1 Seite im Cache · 1 Treffer");
        assertThat(SearchView.LIMIT).as("ein Bildschirm, nicht der Höchstwert des Core").isLessThan(50);
    }

    @Test
    void anUnreachableSourceIsNamedAndTheResultMarkedIncomplete() throws Exception {
        start(true);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SearchView(context);
            view[0].search("betrieb");
        });
        JTable table = find(whole(view[0]), JTable.class).get(0);
        loadAll(table, button(whole(view[0]), "Weitere laden"), 2 * PAGES_PER_SOURCE);
        assertThat(view[0].statusText()).contains("Offline: nicht erreichbar", "unvollständig");
    }

    @Test
    void searchIsKeywordOnlyWithADisabledSemanticCheckbox() throws Exception {
        start(false);

        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        AbstractButton semantic = button(whole(view[0]), "Semantische Suche (experimentell)");
        JTable table = find(whole(view[0]), JTable.class).get(0);
        List<String> columns = new ArrayList<>();
        for (int column = 0; column < table.getModel().getColumnCount(); column++) {
            columns.add(table.getModel().getColumnName(column));
        }

        assertThat(semantic.isEnabled()).isFalse();
        assertThat(semantic.isSelected()).isFalse();
        assertThat(columns).containsExactly("Titel", "Quelle", "Space", "Auszug", "Geändert");
        assertThat(find(whole(view[0]), AbstractButton.class)).extracting(AbstractButton::getText)
                .doesNotContain("Relevanz", "Titel", "Neueste");
    }

    @Test
    void theSemanticCheckboxIsTheRelevanceSearchOnceAnEmbeddingServiceIsConfigured() throws Exception {
        HttpServer service = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        service.createContext("/v1/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = """
                    {"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.6,0.8,0.0]}]}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        service.start();
        servers.add(service);
        start(false, """
                  ai:
                    base-url: "http://127.0.0.1:%d/v1"
                    embedding-model: "text-embedding-3-small"
                    embedding-dimensions: 3
                """.formatted(service.getAddress().getPort()));

        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        assertThat(view[0].semantic.isEnabled()).isTrue();
        assertThat(view[0].semantic.isSelected()).as("Vorgabe bleibt die Schlagwortsuche").isFalse();
        SwingUtilities.invokeAndWait(() -> {
            view[0].semantic.setSelected(true);
            view[0].search("Betrieb der Plattform");
        });

        // Leerer Cache: ein erfolgreiches, leeres Ergebnis der Relevanzsuche statt eines Fehlers oder Titeltreffern.
        await(() -> view[0].statusText().contains("semantisch"));
        assertThat(view[0].statusText()).startsWith("0 Treffer").doesNotContain("unvollständig");
    }

    @Test
    void allSourcesCanBeRestrictedToChosenSources() throws Exception {
        start(false);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        // Der Umfang kommt aus dem Explorer: ohne Auswahl alle Wissensquellen, sonst die gewählten.
        await(() -> context.runtime() != null);
        SwingUtilities.invokeAndWait(() -> view[0].scope(List.of()));
        assertThat(view[0].scopeLabel.getText()).isEqualTo("In: Alle Wissensquellen");
        assertThat(view[0].everywhere.isVisible()).isFalse();
        SwingUtilities.invokeAndWait(() -> {
            view[0].scope(List.of(new Explorer.Scope("beta", null)));
            view[0].search("betrieb");
        });
        assertThat(view[0].scopeLabel.getText()).isEqualTo("In: Beta");
        assertThat(view[0].everywhere.isVisible()).isTrue();
        assertThat(view[0].chosenSources()).containsExactly("beta");
        assertThat(view[0].chosenSpaces()).isEmpty();
        JTable table = find(whole(view[0]), JTable.class).get(0);
        AbstractButton more = button(whole(view[0]), "Weitere laden");
        loadAll(table, more, PAGES_PER_SOURCE);
        Set<String> sourcesSeen = new HashSet<>();
        SwingUtilities.invokeAndWait(() -> {
            for (int row = 0; row < table.getRowCount(); row++) {
                sourcesSeen.add((String) table.getModel().getValueAt(row, 1));
            }
        });
        assertThat(sourcesSeen).containsExactly("beta");
        assertThat(view[0].statusText()).doesNotContain("Dateien");

        // „Überall suchen“ hebt die Auswahl auf: Suche ohne sourceIds.
        SwingUtilities.invokeAndWait(() -> {
            view[0].everywhere.doClick();
            view[0].search("betrieb");
        });
        assertThat(view[0].chosenSources()).isEmpty();
        assertThat(view[0].scopeLabel.getText()).isEqualTo("In: Alle Wissensquellen");
        loadAll(table, more, 2 * PAGES_PER_SOURCE);
    }

    private void start(boolean withOffline) throws Exception {
        start(withOffline, "");
    }

    /** @param ai ein {@code ai:}-Block unter {@code space-harvester} oder leer */
    private void start(boolean withOffline, String ai) throws Exception {
        StringBuilder sources = new StringBuilder();
        for (String id : List.of("alpha", "beta")) {
            FakeConfluence confluence = new FakeConfluence();
            confluence.space("DEV", "Entwicklung");
            for (int i = 0; i < PAGES_PER_SOURCE; i++) {
                String pageId = String.valueOf(1000 + i);
                confluence.page(pageId, "Betrieb %03d".formatted(i), "DEV", "<p>Text</p>").storage("<p>Text</p>");
                confluence.wrappedSearchResult(pageId, "Auszug zu Betrieb %03d".formatted(i));
            }
            sources.append(confluence(id, Character.toUpperCase(id.charAt(0)) + id.substring(1), serve(confluence)));
        }
        Path files = Files.createDirectories(home.resolve("dateien"));
        Files.writeString(files.resolve("betrieb.md"), "# Betrieb\n");
        sources.append("""
                    - id: dateien
                      type: files
                      title: Dateien
                      options:
                        directory: %s
                """.formatted(files));
        if (withOffline) {
            int port;
            try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                port = free.getLocalPort();
            }
            sources.append(confluence("offline", "Offline", URI.create("http://127.0.0.1:" + port + "/wiki")));
        }
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                %s  sources:
                %s""".formatted(home.resolve("data"), ai, sources));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
    }

    private static String confluence(String id, String title, URI baseUrl) {
        return """
                    - id: %s
                      type: confluence
                      title: %s
                      base-url: %s
                """.formatted(id, title, baseUrl);
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

    private static void await(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 600; i++) {
            boolean[] done = new boolean[1];
            SwingUtilities.invokeAndWait(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Bedingung nicht erreicht");
    }

    private static AbstractButton button(Container container, String text) {
        return find(container, AbstractButton.class).stream().filter(button -> text.equals(button.getText()))
                .findFirst().orElseThrow();
    }

    private static <T> List<T> find(Container container, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof Container nested) {
                found.addAll(find(nested, type));
            }
        }
        return found;
    }
}
