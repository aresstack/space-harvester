package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.eakte.EakteEntry;
import com.aresstack.spaceharvester.deck.eakte.EakteSession;
import com.aresstack.spaceharvester.deck.eakte.EakteSessions;
import com.aresstack.spaceharvester.deck.eakte.EakteState;
import com.aresstack.spaceharvester.deck.eakte.http.FakeEakteServer;
import com.aresstack.spaceharvester.deck.eakte.http.HttpEakteServer;
import com.aresstack.spaceharvester.deck.http.HttpClientFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der Bereich „E-Akte“ gegen den Nachbau {@link FakeEakteServer}; alle Daten erfunden. */
class EakteWorkspaceTest {

    private static final String ENTRY = "E-Akte Flotte";
    private static final List<String> KEYS = List.of(EakteConnectForm.ENTRY_KEY,
            EakteConnectForm.START_KEY, EakteConnectForm.USE_PROXY_KEY, EakteConnectForm.WEBDAV_PATH_KEY);

    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
    private FakeEakteServer fake;
    private EakteWorkspace workspace;

    @TempDir
    Path downloads;

    @BeforeEach
    @AfterEach
    void clean() throws Exception {
        if (workspace != null) {
            SwingUtilities.invokeAndWait(workspace::shutdown);
            workspace = null;
        }
        if (fake != null) {
            fake.close();
            fake = null;
        }
        KEYS.forEach(key -> AppState.shared().put(key, (String) null));
        DeckLogin.guard().succeeded(ENTRY);
    }

    @AfterEach
    void stop() {
        background.shutdownNow();
    }

    private String keePassUrl = "";

    private ServerAccounts accounts(String password) {
        ServerAccount account = new ServerAccount(ENTRY, keePassUrl, FakeEakteServer.USER);
        return new ServerAccounts() {
            @Override
            public List<ServerAccount> accounts() {
                return List.of(account, new ServerAccount("Mainframe", "host", "KKR"));
            }

            @Override
            public ServerCredentials credentials(String entry) {
                return new ServerCredentials(account, password.toCharArray());
            }
        };
    }

    private void build(String password) throws Exception {
        fake = new FakeEakteServer();
        EakteSessions sessions = new EakteSessions(
                new HttpEakteServer(HttpClientFactory.direct(), Duration.ofSeconds(10), null), background);
        SwingUtilities.invokeAndWait(() -> workspace = new EakteWorkspace(sessions, accounts(password)));
    }

    private void fillForm() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EakteConnectForm form = workspace.form();
            form.entries(List.of(ENTRY));
            form.entry.setSelectedItem(ENTRY);
            form.start.setText(fake.webdav().resolve("/").toString());
        });
    }

    @Test
    void withoutOwnEntryShowsTheFormAndAsksNothing() throws Exception {
        build(FakeEakteServer.PASSWORD);
        AppState.shared().put("deck.terminal.entry", "Mainframe");
        try {
            SwingUtilities.invokeAndWait(() -> workspace.connect());
            assertThat(onEdt(() -> workspace.selected())).as("nie der Eintrag des Mainframes").isNull();
            assertThat(fake.requests()).isEmpty();
        } finally {
            AppState.shared().put("deck.terminal.entry", (String) null);
        }
    }

    @Test
    void connectsListsOpensFoldersAndDownloads() throws Exception {
        build(FakeEakteServer.PASSWORD);
        fillForm();
        SwingUtilities.invokeAndWait(() -> workspace.form().submit());
        await(() -> onEdt(() -> workspace.selected() != null
                && workspace.selected().state() == EakteState.CONNECTED), "angemeldet");
        EakteSession session = onEdt(() -> workspace.selected());
        EakteBrowser browser = onEdt(() -> workspace.browser(session));
        await(() -> onEdt(() -> browser.entries().size() == 5), "Wurzel gelesen");
        assertThat(onEdt(() -> browser.entries()).stream().map(EakteEntry::name))
                .containsExactly("Ablage", "Arbeitsvorrat", "Gesperrt", "Pinnwand", "Übersicht Akten.txt");
        assertThat(onEdt(() -> browser.status().text())).contains("nur lesen");
        assertThat(EakteConnectForm.savedEntry()).isEqualTo(ENTRY);
        assertThat(fake.passwordPosts()).isEqualTo(1);

        // Leer ist leer, ein Fehler ist ein Fehler (nie ein „leerer“ Ordner).
        String root = onEdt(browser::folder);
        SwingUtilities.invokeAndWait(() -> browser.show(browser.entries().get(3).href()));
        await(() -> onEdt(() -> browser.status().text().contains("leer")), "Pinnwand leer");
        SwingUtilities.invokeAndWait(browser::up);
        await(() -> onEdt(() -> root.equals(browser.folder()) && browser.entries().size() == 5), "zurück");
        SwingUtilities.invokeAndWait(() -> browser.show(browser.entries().get(2).href()));
        await(() -> onEdt(() -> browser.status().tone() == StatusNote.Tone.ERROR), "Gesperrt: Fehler");
        assertThat(onEdt(() -> browser.status().text())).contains("keine Berechtigung").doesNotContain("leer");
        SwingUtilities.invokeAndWait(browser::up);
        await(() -> onEdt(() -> root.equals(browser.folder()) && browser.entries().size() == 5), "wieder zurück");

        // Arbeitsvorrat → Vorgang 4711 → .msg: Speichern unter byte-identisch, Vorschau mit Kopie im Temp-Ordner.
        SwingUtilities.invokeAndWait(() -> browser.show(browser.entries().get(1).href()));
        await(() -> onEdt(() -> browser.entries().size() == 3), "Arbeitsvorrat");
        SwingUtilities.invokeAndWait(() -> browser.show(browser.entries().get(0).href()));
        await(() -> onEdt(() -> browser.entries().size() == 1), "Vorgang 4711");
        assertThat(onEdt(() -> EakteBrowser.type(browser.entries().get(0)))).isEqualTo("Outlook-Nachricht");
        SwingUtilities.invokeAndWait(() -> browser.selectEntry(browser.entries().get(0).href()));
        assertThat(onEdt(() -> browser.entries())).as("Dokument gewählt: Inhalt seiner Mappe bleibt").hasSize(1);
        Path saved = downloads.resolve("Anfrage Frachtraum #7 (Kopie).msg");
        SwingUtilities.invokeAndWait(() -> {
            browser.saveTarget = name -> java.util.Optional.of(downloads.resolve(name));
            browser.saveSelected();
        });
        await(() -> Files.exists(saved), "gespeichert");
        assertThat(Files.readAllBytes(saved)).isEqualTo(FakeEakteServer.MESSAGE);
        try (var files = Files.list(downloads)) {
            assertThat(files).as("keine Teildatei übrig").containsExactly(saved);
        }

        SwingUtilities.invokeAndWait(browser::previewSelected);
        await(() -> onEdt(() -> browser.shownPreview() != null), "Vorschau");
        // Keine zweite Liste neben dem Baum, keine Gliederung, keine eigene Suchzeile: rechts nur das Dokument.
        assertThat(onEdt(() -> components(browser, javax.swing.JTable.class).stream()
                .filter(table -> SwingUtilities.getAncestorOfClass(
                        com.aresstack.spaceharvester.app.ui.extraction.ExtractionView.class, table) == null)
                .toList())).as("keine Tabelle neben dem Baum").isEmpty();
        assertThat(onEdt(() -> components(browser, com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditor.class)
                .get(0).getComponent(2).isVisible())).as("Suchzeile des Editors").isFalse();
        // Umschalter „Vorschau | Extraktion“ in der Titelleiste; Extraktion ohne Analysedienst: nicht analysiert
        assertThat(onEdt(() -> browser.viewToggle().isVisible())).isTrue();
        assertThat(onEdt(() -> workspace.tools().getComponent(0) == browser.viewToggle())).isTrue();
        String href = onEdt(() -> browser.entries().get(0).href());
        assertThat(onEdt(() -> browser.extraction().shown()))
                .isEqualTo(new com.aresstack.spaceharvester.application.extraction.DocumentRef("eakte", href,
                        "\"4711-1\""));
        SwingUtilities.invokeAndWait(() -> ((com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle)
                browser.viewToggle()).select(EakteBrowser.EXTRACTION));
        await(() -> onEdt(() -> browser.status().text().equals("Extraktion: Noch nicht analysiert")),
                "Extraktion nicht analysiert");
        assertThat(onEdt(() -> browser.extraction().actions().isVisible())).isTrue();
        Path temp = onEdt(browser::tempFolder);
        assertThat(temp).isNotNull();

        SwingUtilities.invokeAndWait(() -> workspace.close(session));
        assertThat(Files.exists(temp)).as("Temp-Ordner beim Schließen gelöscht").isFalse();
        assertThat(onEdt(() -> workspace.sessionCount())).isZero();
    }

    @Test
    void failedDownloadLeavesNoPartialFile() throws Exception {
        build(FakeEakteServer.PASSWORD);
        fillForm();
        SwingUtilities.invokeAndWait(() -> workspace.form().submit());
        await(() -> onEdt(() -> workspace.selected() != null
                && workspace.selected().state() == EakteState.CONNECTED), "angemeldet");
        EakteBrowser browser = onEdt(() -> workspace.browser(workspace.selected()));
        await(() -> onEdt(() -> browser.entries().size() == 5), "Wurzel gelesen");
        SwingUtilities.invokeAndWait(() -> browser.show(browser.entries().get(0).href()));
        await(() -> onEdt(() -> browser.entries().size() == 2), "Ablage");
        SwingUtilities.invokeAndWait(() -> {
            browser.entries().stream().filter(entry -> entry.name().equals("Defekt.txt")).findFirst()
                    .ifPresent(entry -> browser.selectEntry(entry.href()));
            browser.saveTarget = name -> java.util.Optional.of(downloads.resolve(name));
            browser.saveSelected();
        });
        await(() -> onEdt(() -> browser.status().tone() == StatusNote.Tone.ERROR), "Serverfehler gemeldet");
        assertThat(onEdt(() -> browser.status().text())).contains("Serverfehler");
        try (var files = Files.list(downloads)) {
            assertThat(files).as("weder Datei noch Teildatei").isEmpty();
        }
    }

    /** Angelo: genau ein Feld „E-Akte-Adresse“; WebDAV und Anmeldedienste bestimmt der Adapter fest. */
    @Test
    void onlyTheStartUrlIsNeededTheRestIsFilledIn() throws Exception {
        build(FakeEakteServer.PASSWORD);
        SwingUtilities.invokeAndWait(() -> {
            EakteConnectForm form = workspace.form();
            assertThat(form.start.getText()).as("keine vorbelegte Adresse").isEmpty();
            form.entries(List.of(ENTRY));
            form.entry.setSelectedItem(ENTRY);
            form.start.setText(fake.webdav().resolve("/").toString());
            form.submit();
        });
        await(() -> onEdt(() -> workspace.selected() != null
                && workspace.selected().state() == EakteState.CONNECTED), "angemeldet nur mit Start-URL");
        assertThat(fake.passwordPosts()).isEqualTo(1);
    }

    /** Angelo: die E-Akte-Adresse kommt aus dem URL-Feld des KeePass-Eintrags, wenn sie dort steht. */
    @Test
    void takesTheAddressFromTheKeePassEntry() throws Exception {
        fake = new FakeEakteServer();
        keePassUrl = fake.webdav().resolve("/").toString(); // nur die Basisadresse, wie in KeePass
        EakteSessions sessions = new EakteSessions(
                new HttpEakteServer(HttpClientFactory.direct(), Duration.ofSeconds(10), null), background);
        SwingUtilities.invokeAndWait(() -> {
            workspace = new EakteWorkspace(sessions, accounts(FakeEakteServer.PASSWORD));
            EakteConnectForm form = workspace.form();
            form.entries(List.of(ENTRY));
            form.entry.setSelectedItem(ENTRY);
            assertThat(form.start.getText()).isEmpty();
            assertThat(form.start.getToolTipText()).contains("Domain", "Subdomains", "/fsc/webdav", "optional");
            form.submit();
        });
        await(() -> onEdt(() -> workspace.selected() != null
                && workspace.selected().state() == EakteState.CONNECTED), "angemeldet mit der URL aus KeePass");
        assertThat(fake.passwordPosts()).isEqualTo(1);
        String remembered = AppState.shared().get(EakteConnectForm.START_KEY);
        assertThat(remembered == null || remembered.isEmpty()).as("nichts Eingetipptes gemerkt").isTrue();
    }

    @Test
    void refusedLoginLocksTheEntryAndNoAutomaticAttemptFollows() throws Exception {
        build("falsch");
        fillForm();
        SwingUtilities.invokeAndWait(() -> workspace.form().submit());
        await(() -> !DeckLogin.guard().mayTry(ENTRY), "Eintrag gesperrt");
        assertThat(fake.passwordPosts()).isEqualTo(1);
        SwingUtilities.invokeAndWait(() -> workspace.connect());
        assertThat(onEdt(() -> workspace.form().problemText())).contains("fehlgeschlagen");
        Thread.sleep(300);
        assertThat(fake.passwordPosts()).as("kein zweiter Versuch").isEqualTo(1);
    }

    private static <T> T onEdt(Supplier<T> read) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> value.set(read.get()));
        return value.get();
    }

    private interface Check {
        boolean ok() throws Exception;
    }

    private static void await(Check check, String what) throws Exception {
        long until = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < until) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Nicht erreicht: " + what);
    }

    private static <T> List<T> components(java.awt.Container root, Class<T> type) {
        List<T> found = new java.util.ArrayList<>();
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof java.awt.Container container) {
                found.addAll(components(container, type));
            }
        }
        return found;
    }
}
