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
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die Suche der App ist die Schlagwortsuche des Core über die Quellen selbst: Treffer zeigen Titel, Quelle, Space
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
        JTable table = find(view[0].component(), JTable.class).get(0);
        AbstractButton more = button(view[0].component(), "Weitere laden");
        await(() -> table.getRowCount() == 100);
        await(more::isEnabled);
        assertThat(view[0].statusText()).contains("100 Treffer", "weitere vorhanden", "Dateien: keine eigene Suche")
                .doesNotContain("unvollständig", "Bestand");

        SwingUtilities.invokeAndWait(more::doClick);
        await(() -> table.getRowCount() == 2 * PAGES_PER_SOURCE);
        await(() -> !more.isEnabled());

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
        assertThat(first).containsExactly("Betrieb 000", "alpha", "DEV", "Auszug zu Betrieb 000");
        assertThat(keys).hasSize(2 * PAGES_PER_SOURCE);
        assertThat(sourcesSeen).containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    void anUnreachableSourceIsNamedAndTheResultMarkedIncomplete() throws Exception {
        start(true);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SearchView(context);
            view[0].search("betrieb");
        });
        JTable table = find(view[0].component(), JTable.class).get(0);
        await(() -> table.getRowCount() == 100);
        assertThat(view[0].statusText()).contains("Offline: nicht erreichbar", "unvollständig");
    }

    @Test
    void searchIsKeywordOnlyWithADisabledSemanticCheckbox() throws Exception {
        start(false);

        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        AbstractButton semantic = button(view[0].component(), "Semantische Suche (experimentell)");
        JTable table = find(view[0].component(), JTable.class).get(0);
        List<String> columns = new ArrayList<>();
        for (int column = 0; column < table.getModel().getColumnCount(); column++) {
            columns.add(table.getModel().getColumnName(column));
        }

        assertThat(semantic.isEnabled()).isFalse();
        assertThat(semantic.isSelected()).isFalse();
        assertThat(columns).containsExactly("Titel", "Quelle", "Space", "Auszug", "Geändert");
        assertThat(find(view[0].component(), AbstractButton.class)).extracting(AbstractButton::getText)
                .doesNotContain("Relevanz", "Titel", "Neueste");
    }

    @Test
    void allSourcesCanBeRestrictedToChosenSources() throws Exception {
        start(false);
        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SearchView(context));
        // Sichtbare Häkchen: „Alle“ ist vorgegeben; die Space-Auswahl zeigt die Spaces der Quellen mit Quelle.
        // Die Häkchen entstehen bei jeder Änderung der Quellen neu (Verbindungsprüfung); daher jedes Mal frisch.
        java.util.function.Supplier<List<JCheckBox>> boxes = () -> find(view[0].component(), JCheckBox.class)
                .stream().filter(JCheckBox::isEnabled).toList();
        await(() -> context.connection("alpha").state() == AppContext.Connection.State.CONNECTED
                && context.connection("beta").state() == AppContext.Connection.State.CONNECTED);
        assertThat(boxes.get()).extracting(JCheckBox::getText).containsExactly("Alle", "Alpha", "Beta", "Dateien");
        assertThat(boxes.get().get(0).isSelected()).isTrue();
        JComboBox<?> spaces = find(view[0].component(), JComboBox.class).get(0);
        await(() -> spaces.getItemCount() >= 3);
        SwingUtilities.invokeAndWait(() -> {
            boxes.get().get(2).doClick();
            view[0].search("betrieb");
        });
        assertThat(boxes.get().get(0).isSelected()).isFalse();
        assertThat(view[0].chosenSources()).containsExactly("beta");
        JTable table = find(view[0].component(), JTable.class).get(0);
        AbstractButton more = button(view[0].component(), "Weitere laden");
        await(() -> table.getRowCount() == PAGES_PER_SOURCE);
        await(() -> !more.isEnabled());
        Set<String> sourcesSeen = new HashSet<>();
        SwingUtilities.invokeAndWait(() -> {
            for (int row = 0; row < table.getRowCount(); row++) {
                sourcesSeen.add((String) table.getModel().getValueAt(row, 1));
            }
        });
        assertThat(sourcesSeen).containsExactly("beta");
        assertThat(view[0].statusText()).doesNotContain("Dateien");

        // „Alle“ hebt die Auswahl auf: Suche ohne sourceIds.
        SwingUtilities.invokeAndWait(() -> {
            boxes.get().get(0).doClick();
            view[0].search("betrieb");
        });
        assertThat(view[0].chosenSources()).isEmpty();
        assertThat(boxes.get().get(2).isSelected()).isFalse();
        await(() -> table.getRowCount() == 100 && more.isEnabled());
    }

    private void start(boolean withOffline) throws Exception {
        StringBuilder sources = new StringBuilder();
        for (String id : List.of("alpha", "beta")) {
            FakeConfluence confluence = new FakeConfluence();
            confluence.space("DEV", "Entwicklung");
            for (int i = 0; i < PAGES_PER_SOURCE; i++) {
                String pageId = String.valueOf(1000 + i);
                confluence.page(pageId, "Betrieb %03d".formatted(i), "DEV", "<p>Text</p>");
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
                  sources:
                %s""".formatted(home.resolve("data"), sources));
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
