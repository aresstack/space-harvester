package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.ui.comic.bubble.AgentActivityBubblePanel;
import com.aresstack.enterpriseai.ui.comic.bubble.BubbleMessageRow;
import com.aresstack.spaceharvester.app.ui.markdown.AssistantMarkdownMessage;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection;
import com.sun.net.httpserver.HttpServer;
import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wie im EAC (Angelo 24.11.2026): Während der Recherche stehen die blauen Quellen unter der gelben Blase, die keine
 * Quellenzeile mehr hat. Schreibt die Antwort, platzt die gelbe Blase; danach erscheint die grüne Antwort an ihrer
 * Stelle, ohne Lücke, und die Quellen stehen darunter. Das Modell ist ein erfundener lokaler Dienst.
 */
class ChatBurstTest {

    @TempDir
    Path home;

    private AppContext context;
    private HttpServer model;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
        if (model != null) {
            model.stop(0);
        }
    }

    @Test
    void theYellowBubbleBurstsAndTheAnswerTakesItsPlaceWithTheSourcesBelow() throws Exception {
        Path files = Files.createDirectories(home.resolve("zoo"));
        Files.writeString(files.resolve("giraffe.md"), "# Giraffe\n\nDie Giraffe frisst Akazienblätter.\n");
        CountDownLatch answerAsked = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> last = new java.util.concurrent.atomic.AtomicReference<>();
        String ref = "zoo/" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("giraffe.md".getBytes(StandardCharsets.UTF_8));
        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            last.set(request);
            List<String> chunks;
            if (calls.getAndIncrement() == 0) {
                String args = "{\\\"kennung\\\":\\\"" + ref + "\\\"}";
                chunks = List.of("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":"
                        + "{\"name\":\"seite_lesen\",\"arguments\":\"" + args + "\"}}]},\"finish_reason\":null}]}",
                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
            } else {
                answerAsked.countDown();
                try {
                    answer.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                chunks = List.of("{\"choices\":[{\"delta\":{\"content\":\"Die **Giraffe** frisst \"}}]}",
                        "{\"choices\":[{\"delta\":{\"content\":\"Akazienblätter. [1]\"}}]}",
                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (String chunk : chunks) {
                    out.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        model.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        model.setExecutor(Executors.newCachedThreadPool());
        model.start();
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  ai:
                    base-url: http://127.0.0.1:%d/v1
                    chat-model: test-chat
                    external-processing-sources:
                      - zoo
                  sources:
                    - id: zoo
                      type: files
                      title: Zoo
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), model.getAddress().getPort(), files));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        var workspace = context.runtime().workspace();
        String space = workspace.spaces("zoo").get(0).key();
        workspace.startSync("zoo", space, com.aresstack.spaceharvester.application.sync.SyncMode.FULL);
        await(() -> workspace.resolveSelection(com.aresstack.spaceharvester.application.holdings.Selection.of(
                com.aresstack.spaceharvester.application.holdings.Selection.Root.space("zoo", space)))
                .counts().stored() >= 1);

        ChatPanel[] panel = new ChatPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new ChatPanel(context);
            panel[0].setSize(460, 760);
            panel[0].selection(new Selection.Node("zoo", space, null, "Zoo"));
        });
        try {
            await(() -> onEdt(() -> panel[0].sendButton().isEnabled()));
        } catch (AssertionError e) {
            String[] state = new String[1];
            SwingUtilities.invokeAndWait(() -> state[0] = panel[0].knowledge() + " | " + panel[0].status());
            throw new AssertionError("Senden gesperrt: " + state[0], e);
        }
        SwingUtilities.invokeAndWait(() -> {
            ((JTextComponent) panel[0].editor()).setText("Was frisst die Giraffe?");
            panel[0].send();
        });
        assertThat(answerAsked.await(60, TimeUnit.SECONDS)).as("Seite gelesen, Antwort angefragt").isTrue();
        try {
            await(() -> onEdt(() -> sourcesShown(panel[0])));
        } catch (AssertionError e) {
            String body = last.get();
            int tool = Math.max(0, body.indexOf("\"tool\""));
            throw new AssertionError("keine Quellen; Werkzeugantwort: " + body.substring(tool,
                    Math.min(body.length(), tool + 600)), e);
        }

        // Recherche: gelbe Blase, darunter die blauen Quellen; in der Blase keine Quellenzeile.
        int[] thoughtY = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            List<Component> rows = panel[0].transcriptRows();
            int thought = indexOf(rows, row -> thought(row) != null);
            int links = indexOf(rows, row -> row instanceof ChatPanel.SourceLinks);
            assertThat(thought).isNotNegative();
            assertThat(links).as("Quellen unter der gelben Blase").isGreaterThan(thought);
            assertThat(indexOf(rows, row -> row instanceof AssistantMarkdownMessage)).as("noch keine Antwort")
                    .isNegative();
            assertThat(thought(rows.get(thought)).getExplanation()).doesNotContain("Quellen:");
            layoutAll(panel[0]);
            thoughtY[0] = rows.get(thought).getY();
            shot(panel[0], "1-recherche");
        });

        answer.countDown();
        // Die Antwort schreibt: Die gelbe Blase platzt, die Quellen sind solange weg, die Antwort noch nicht da.
        await(() -> onEdt(() -> {
            List<Component> rows = panel[0].transcriptRows();
            return indexOf(rows, row -> thought(row) != null) >= 0 && !sourcesShown(panel[0])
                    && indexOf(rows, row -> row instanceof AssistantMarkdownMessage) < 0;
        }));
        SwingUtilities.invokeAndWait(() -> {
            layoutAll(panel[0]);
            shot(panel[0], "2-platzt");
        });
        await(() -> onEdt(() -> {
            List<Component> rows = panel[0].transcriptRows();
            return indexOf(rows, row -> row instanceof AssistantMarkdownMessage) >= 0
                    && indexOf(rows, row -> thought(row) != null) < 0 && sourcesShown(panel[0]);
        }));
        SwingUtilities.invokeAndWait(() -> {
            layoutAll(panel[0]);
            List<Component> rows = panel[0].transcriptRows();
            int answerRow = indexOf(rows, row -> row instanceof AssistantMarkdownMessage);
            AssistantMarkdownMessage message = (AssistantMarkdownMessage) rows.get(answerRow);
            assertThat(message.sources()).as("Quellen im Antwort-Footer").isInstanceOf(ChatPanel.SourceLinks.class);
            assertThat(message.sources().isVisible()).isTrue();
            assertThat(rows.get(answerRow).getY()).as("Antwort genau an der Stelle der gelben Blase, ohne Lücke")
                    .isEqualTo(thoughtY[0]);
            shot(panel[0], "3-antwort");
        });
    }

    private static AgentActivityBubblePanel thought(Component row) {
        if (row instanceof BubbleMessageRow message) {
            for (Component child : message.getComponents()) {
                if (child instanceof AgentActivityBubblePanel bubble) {
                    return bubble;
                }
            }
        }
        return null;
    }

    private static boolean sourcesShown(ChatPanel panel) {
        return panel.sourceLinks().stream().anyMatch(Component::isVisible);
    }

    private static int indexOf(List<Component> rows, java.util.function.Predicate<Component> test) {
        for (int i = 0; i < rows.size(); i++) {
            if (test.test(rows.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static void layoutAll(Component component) {
        component.doLayout();
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                layoutAll(child);
            }
        }
    }

    /** Legt ein Bild des Chats unter build/chat-burst ab (Beleg für den Ablauf). */
    private static void shot(ChatPanel panel, String name) {
        try {
            BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = image.createGraphics();
            panel.paint(g);
            g.dispose();
            Path dir = Files.createDirectories(Path.of("build", "chat-burst"));
            javax.imageio.ImageIO.write(image, "png", dir.resolve(name + ".png").toFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static boolean onEdt(Supplier<Boolean> probe) throws Exception {
        boolean[] result = new boolean[1];
        SwingUtilities.invokeAndWait(() -> result[0] = probe.get());
        return result[0];
    }

    private interface Probe {
        boolean test() throws Exception;
    }

    private static void await(Probe probe) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!probe.test()) {
            assertThat(System.nanoTime()).as("Zeit abgelaufen").isLessThan(until);
            Thread.sleep(50);
        }
    }
}
