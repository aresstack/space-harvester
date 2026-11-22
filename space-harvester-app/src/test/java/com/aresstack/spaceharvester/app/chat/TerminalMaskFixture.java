package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.deck.terminal.SessionState;
import com.aresstack.spaceharvester.deck.terminal.TerminalProfile;
import com.aresstack.spaceharvester.deck.terminal.TerminalSession;
import com.aresstack.spaceharvester.deck.terminal.TerminalSessions;
import com.aresstack.spaceharvester.deck.terminal.TerminalView;
import com.aresstack.spaceharvester.deck.terminal.openterm.FakeTn3270Host;
import com.aresstack.spaceharvester.deck.terminal.openterm.OpenTermConnector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;

/** Gemeinsames für die Tests des Maskenassistenten im Chat (#1151): Schein-Host mit Dataset-Maske, Laufzeit. */
final class TerminalMaskFixture implements AutoCloseable {

    /** Eine Maske „Neues Dataset anlegen“ wie ISPF 3.2, erfunden. */
    static final List<FakeTn3270Host.Line> ALLOCATE = List.of(
            new FakeTn3270Host.Line(0, 2, "ALLOCATE NEW DATA SET", false),
            new FakeTn3270Host.Line(2, 2, "Data Set Name . . .", false),
            FakeTn3270Host.Line.input(2, 24, 44, false, false),
            new FakeTn3270Host.Line(4, 2, "Space units . . . .", false),
            FakeTn3270Host.Line.input(4, 24, 9, false, false),
            new FakeTn3270Host.Line(5, 2, "Primary quantity  .", false),
            FakeTn3270Host.Line.input(5, 24, 5, true, false),
            new FakeTn3270Host.Line(6, 2, "Record format . . .", false),
            FakeTn3270Host.Line.input(6, 24, 2, false, false),
            new FakeTn3270Host.Line(7, 2, "Record length . . .", false),
            FakeTn3270Host.Line.input(7, 24, 5, true, false),
            new FakeTn3270Host.Line(9, 2, "Password  . . . . .", false),
            FakeTn3270Host.Line.input(9, 24, 8, false, true),
            new FakeTn3270Host.Line(22, 2, "Enter = Anlegen   F3 = Ende", false));

    final FakeTn3270Host host;
    final TerminalSessions sessions;
    final TerminalSession session;
    final TerminalView view;

    TerminalMaskFixture() throws Exception {
        host = new FakeTn3270Host(ALLOCATE, "Cp273");
        sessions = new TerminalSessions(new OpenTermConnector(), runnable -> Thread.ofVirtual().start(runnable));
        session = sessions.open(TerminalProfile.of("127.0.0.1", host.port()));
        view = session.view(TerminalView.class).orElseThrow();
        await(() -> session.state() == SessionState.CONNECTED && view.text().contains("ALLOCATE NEW DATA SET")
                && !view.keyboardLocked());
    }

    String line(int row) {
        return view.text().split("\n")[row];
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("Zeitüberschreitung");
            }
            Thread.sleep(50);
        }
    }

    /** Eine kleine Laufzeit für den Turn (er braucht einen Workspace, auch wenn die Terminal-Werkzeuge ihn nicht lesen). */
    static AppContext runtime(Path home, String aiYaml) throws Exception {
        Path files = Files.createDirectories(home.resolve("leer"));
        Files.writeString(files.resolve("hinweis.md"), "# Hinweis\n\nNichts.\n");
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                %s  sources:
                    - id: leer
                      type: files
                      title: Leer
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), aiYaml, files));
        AppContext context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config,
                "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        if (!started.await(60, TimeUnit.SECONDS)) {
            throw new AssertionError("Laufzeit startet nicht");
        }
        return context;
    }

    static Workspace workspace(AppContext context) {
        return context.runtime().workspace();
    }

    @Override
    public void close() throws Exception {
        session.disconnect();
        host.close();
    }

    /** Zeichnet die Anfragen auf und reicht sie weiter. */
    static class Recording implements AiChat {
        final AiChat target;
        final List<Request> requests = new CopyOnWriteArrayList<>();

        Recording(AiChat target) {
            this.target = target;
        }

        @Override
        public Task stream(Request request, Listener listener) {
            requests.add(request);
            return target.stream(request, listener);
        }
    }

    static final class Events implements ChatTurn.Listener {
        final CountDownLatch done = new CountDownLatch(1);
        final List<String> using = new CopyOnWriteArrayList<>();
        final StringBuilder deltas = new StringBuilder();
        volatile String completed;
        volatile String failed;
        volatile String cancelled;

        @Override
        public void context(ChatKnowledge.Context context) {
        }

        @Override
        public void using(String what) {
            using.add(what);
        }

        @Override
        public void delta(String text) {
            synchronized (deltas) {
                deltas.append(text);
            }
        }

        @Override
        public void completed(String text) {
            completed = text;
            done.countDown();
        }

        @Override
        public void failed(String message) {
            failed = message;
            done.countDown();
        }

        @Override
        public void cancelled(String partial) {
            cancelled = partial;
            done.countDown();
        }
    }

    static List<String> toolNames(AiChat.Request request) {
        List<String> names = new ArrayList<>();
        request.tools().forEach(tool -> names.add(tool.name()));
        return names;
    }
}
