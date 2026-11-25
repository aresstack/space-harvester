package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.editor.EditorSession;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.deck.files.FileBrowser;
import com.aresstack.spaceharvester.deck.files.local.LocalDocumentStore;
import com.aresstack.spaceharvester.deck.files.local.LocalFileSource;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

/** Arbeitsbereich Dateien (#1142): Liste, Filter, Anlegen, Editor mit Speichern – gegen echte Dateien. */
class FilesWorkspaceTest {

    private static final Executor NOW = Runnable::run;

    @TempDir
    Path root;

    private FilesWorkspace workspace;
    private EditorSessions editors;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectory(root.resolve("Berichte"));
        Files.writeString(root.resolve("notiz.txt"), "Grüße an Bord\n");
        Files.writeString(root.resolve("job.jcl"), "//JOB1 JOB\n");
        Files.writeString(root.resolve("daten.json"), "{\"a\": 1}\n");
        AppState.shared().put(FilesWorkspace.LOCATION_KEY, root.toString());
        AppState.shared().put(FilesWorkspace.SORT_KEY, "NAME_ASC");
        AppState.shared().put(FilesWorkspace.GROUP_KEY, false);
        AppState.shared().put(FilesWorkspace.DETAILS_KEY, true);
        AppState.shared().put(FilesWorkspace.BOOKMARKS_KEY, "");
        editors = new EditorSessions(NOW);
        SwingUtilities.invokeAndWait(() -> workspace = new FilesWorkspace(
                new FileBrowser(new LocalFileSource(FileSystems.getDefault(), root, List.of()), NOW), editors,
                new LocalDocumentStore()));
        flush();
    }

    @Test
    void readsNothingUntilShownThenListsTheLastFolder() throws Exception {
        assertThat(workspace.browser().location()).as("Öffnen der Perspektive liest keine Dateien").isEmpty();

        start();

        assertThat(names()).containsExactly("Berichte", "daten.json", "job.jcl", "notiz.txt");
        assertThat(workspace.pathField().getText()).isEqualTo(root.toString());
        assertThat(workspace.statusText()).isEqualTo("4 Einträge");
        assertThat(workspace.pillTitles()).containsExactly(root.getFileName().toString());
    }

    @Test
    void filtersAndOpensFolders() throws Exception {
        start();

        SwingUtilities.invokeAndWait(() -> workspace.filterField().setText("\\.jcl$"));
        flush();
        assertThat(names()).containsExactly("job.jcl");
        assertThat(workspace.statusText()).isEqualTo("4 Einträge · 1 passen zum Filter");

        SwingUtilities.invokeAndWait(() -> workspace.filterField().setText(""));
        flush();
        SwingUtilities.invokeAndWait(() -> workspace.openRow(0));
        flush();
        assertThat(workspace.pathField().getText()).isEqualTo(root.resolve("Berichte").toString());
        assertThat(workspace.statusText()).isEqualTo("0 Einträge");
    }

    @Test
    void explorerKeepsAncestorsAndOpensFilesWhileAddressFollowsTabs() throws Exception {
        start();
        String folder = root.resolve("Berichte").toString();
        String file = root.resolve("job.jcl").toString();
        SwingUtilities.invokeAndWait(() -> {
            var tree = workspace.navigationTree();
            assertThat(tree.pathFor(root.toString())).isNotNull();
            assertThat(tree.pathFor(folder)).isNotNull();
            tree.expandPath(tree.pathFor(folder));
        });
        flush();
        assertThat(workspace.pathField().getText()).isEqualTo(folder);
        SwingUtilities.invokeAndWait(() -> {
            var tree = workspace.navigationTree();
            assertThat(tree.pathFor(root.toString())).isNotNull();
            assertThat(tree.pathFor(file)).isNotNull();
            tree.setSelectionPath(tree.pathFor(file));
            tree.getActionMap().get("open-file").actionPerformed(null);
        });
        flush();
        assertThat(workspace.selected().location()).isEqualTo(file);
        assertThat(workspace.pathField().getText()).isEqualTo(file);
        SwingUtilities.invokeAndWait(() -> workspace.select(null));
        assertThat(workspace.pathField().getText()).isEqualTo(folder);
    }

    @Test
    void createsAFolderFromTheNameRow() throws Exception {
        start();

        SwingUtilities.invokeAndWait(() -> {
            workspace.newFolder();
            assertThat(workspace.nameRowShown()).isTrue();
            workspace.nameField().setText("Neu");
            workspace.confirmName();
        });
        flush();

        assertThat(Files.isDirectory(root.resolve("Neu"))).isTrue();
        assertThat(workspace.nameRowShown()).isFalse();
        assertThat(names()).contains("Neu");
        assertThat(workspace.statusText()).isEqualTo("Ordner „Neu“ angelegt.");
    }

    @Test
    void anInvalidNameKeepsTheRowOpenAndSaysWhy() throws Exception {
        start();

        SwingUtilities.invokeAndWait(() -> {
            workspace.newFile();
            workspace.nameField().setText("a/b");
            workspace.confirmName();
        });
        flush();

        assertThat(workspace.nameRowShown()).isTrue();
        assertThat(workspace.statusText()).startsWith("Der Name darf keines dieser Zeichen enthalten");
    }

    @Test
    void aDocumentSavedUnderANewNameKeepsItsTab() throws Exception {
        start();
        int row = names().indexOf("job.jcl");
        SwingUtilities.invokeAndWait(() -> workspace.openRow(row));
        flush();
        EditorSession session = workspace.selected();

        session.saveAs(root.resolve("kopie.jcl").toString());
        flush();
        SwingUtilities.invokeAndWait(() -> workspace.select(null));
        SwingUtilities.invokeAndWait(() -> workspace.select(session));
        flush();

        assertThat(session.location()).isEqualTo(root.resolve("kopie.jcl").toString());
        assertThat(workspace.pillTitles()).containsExactly(root.getFileName().toString(), "kopie.jcl");
        boolean[] shown = new boolean[1];
        SwingUtilities.invokeAndWait(() -> shown[0] = workspace.editor(session).isVisible());
        assertThat(shown[0]).isTrue();
    }

    @Test
    void opensATextFileInTheSharedEditorAndSavesIt() throws Exception {
        start();
        int row = names().indexOf("job.jcl");

        SwingUtilities.invokeAndWait(() -> workspace.openRow(row));
        flush();

        EditorSession session = workspace.selected();
        assertThat(session).isNotNull();
        assertThat(editors.all()).containsExactly(session);
        assertThat(workspace.pillTitles()).containsExactly(root.getFileName().toString(), "job.jcl");
        assertThat(workspace.editor(session).text()).isEqualTo("//JOB1 JOB\n");

        SwingUtilities.invokeAndWait(() -> workspace.editor(session).type("//* Kommentar\n"));
        flush();
        assertThat(session.dirty()).isTrue();
        assertThat(workspace.statusText()).contains("ungespeicherte Änderungen");

        session.save();
        flush();
        assertThat(Files.readString(root.resolve("job.jcl"))).isEqualTo("//* Kommentar\n//JOB1 JOB\n");
        assertThat(workspace.statusText()).contains("Gespeichert");

        // Ein zweites Öffnen derselben Datei holt den offenen Reiter nach vorn.
        SwingUtilities.invokeAndWait(() -> workspace.select(null));
        SwingUtilities.invokeAndWait(() -> workspace.openRow(row));
        flush();
        assertThat(workspace.pillTitles()).containsExactly(root.getFileName().toString(), "job.jcl");
        assertThat(workspace.selected()).isSameAs(session);
    }

    @Test
    void showsAConflictAndOverwritesOnlyWhenAsked() throws Exception {
        start();
        int row = names().indexOf("notiz.txt");
        SwingUtilities.invokeAndWait(() -> workspace.openRow(row));
        flush();
        EditorSession session = workspace.selected();
        Files.writeString(root.resolve("notiz.txt"), "von außen geändert\n");

        SwingUtilities.invokeAndWait(() -> workspace.editor(session).type("Neu: "));
        flush();
        session.save();
        flush();

        assertThat(session.state()).isEqualTo(EditorSession.State.CONFLICT);
        assertThat(workspace.editor(session).plateSentence()).startsWith("Die Datei wurde seit dem Öffnen geändert.");
        assertThat(Files.readString(root.resolve("notiz.txt"))).isEqualTo("von außen geändert\n");

        SwingUtilities.invokeAndWait(() -> workspace.editor(session).clickPlateAction());
        flush();
        assertThat(Files.readString(root.resolve("notiz.txt"))).isEqualTo("Neu: Grüße an Bord\n");
    }

    @Test
    void showsReloadedContentEvenIfTheLoadingStateWasNeverSeen() throws Exception {
        start();
        int row = names().indexOf("notiz.txt");
        SwingUtilities.invokeAndWait(() -> workspace.openRow(row));
        flush();
        EditorSession session = workspace.selected();
        Files.writeString(root.resolve("notiz.txt"), "neu geladen\n");

        // Das Laden ist fertig, bevor der EDT die Meldungen abarbeitet: beide sehen nur noch „Bereit“.
        session.load();
        flush();

        assertThat(workspace.editor(session).text()).isEqualTo("neu geladen\n");
        assertThat(session.dirty()).isFalse();
    }

    @Test
    void picksTheSyntaxByExtension() throws Exception {
        start();
        int row = names().indexOf("daten.json");
        SwingUtilities.invokeAndWait(() -> workspace.openRow(row));
        flush();

        assertThat(workspace.editor(workspace.selected()).syntaxStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_JSON);
    }

    @Test
    void commandDeckShowsTheFilesAreaAndSharesTheEditor() throws Exception {
        AtomicReference<CommandDeck> deck = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            deck.set(new CommandDeck());
            deck.get().show(DeckArea.FILES);
            assertThat(deck.get().area()).isEqualTo(DeckArea.FILES);
            assertThat(DeckArea.FILES.ready()).isTrue();
            assertThat(deck.get().files()).isNotNull();
            assertThat(deck.get().editors().all()).isEmpty();
        });
        deck.get().shutdown();
    }

    @Test
    void groupsByExtensionAndCollapsesGroups() throws Exception {
        start();
        SwingUtilities.invokeAndWait(() -> workspace.group(true, true));
        flush();

        assertThat(labels()).containsExactly("Ordner (1)", "Berichte", "JCL (1)", "job.jcl", "JSON (1)",
                "daten.json", "TXT (1)", "notiz.txt");

        SwingUtilities.invokeAndWait(() -> workspace.openRow(2));
        flush();
        assertThat(labels()).containsExactly("Ordner (1)", "Berichte", "JCL (1)", "JSON (1)", "daten.json",
                "TXT (1)", "notiz.txt");
        assertThat(workspace.browser().location()).contains(root.toString());
        SwingUtilities.invokeAndWait(() -> workspace.group(false, true));
        flush();
        assertThat(names()).containsExactly("Berichte", "daten.json", "job.jcl", "notiz.txt");
    }

    @Test
    void hidesDetailsAndKeepsBookmarks() throws Exception {
        start();
        SwingUtilities.invokeAndWait(() -> workspace.detailsShown(false));
        assertThat(workspace.columnCount()).isEqualTo(1);
        SwingUtilities.invokeAndWait(() -> workspace.detailsShown(true));
        assertThat(workspace.columnCount()).isEqualTo(3);

        SwingUtilities.invokeAndWait(() -> workspace.toggleBookmark(root.toString()));
        assertThat(workspace.bookmarks()).containsExactly(root.toString());
        SwingUtilities.invokeAndWait(() -> workspace.toggleBookmark(root.toString()));
        assertThat(workspace.bookmarks()).isEmpty();
    }

    @Test
    void sharePointAddressNeedsWindows() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        start();

        SwingUtilities.invokeAndWait(() -> workspace.go("https://intranet.example.org/sites/Raum"));
        flush();

        assertThat(workspace.statusText()).startsWith("SharePoint über WebDAV geht nur unter Windows");
        assertThat(workspace.browser().location()).contains(root.toString());
    }

    @Test
    void opensMoreFolderTabsEachWithItsOwnFolderAndRemembersThem() throws Exception {
        AppState.shared().put(FilesWorkspace.TABS_KEY, "");
        start();
        String top = root.getFileName().toString();

        SwingUtilities.invokeAndWait(() -> workspace.newTab(root.resolve("Berichte").toString()));
        flush();
        assertThat(workspace.pillTitles()).containsExactly(top, "Berichte");
        assertThat(workspace.browser().location()).contains(root.resolve("Berichte").toString());
        assertThat(names()).isEmpty();
        assertThat(AppState.shared().get(FilesWorkspace.TABS_KEY)).isEqualTo(root.resolve("Berichte") + "\n");
        assertThat(AppState.shared().get(FilesWorkspace.LOCATION_KEY)).as("erster Reiter bleibt")
                .isEqualTo(root.toString());

        // Zurück zum ersten Reiter: dessen Ordner, Verlauf und Liste.
        SwingUtilities.invokeAndWait(() -> workspace.showBrowseTab(0));
        flush();
        assertThat(workspace.browser().location()).contains(root.toString());
        assertThat(names()).contains("Berichte", "job.jcl");

        // Der Filter gilt für den gezeigten Reiter (Codex #1241): im zweiten Reiter filtern, der erste bleibt ganz.
        SwingUtilities.invokeAndWait(() -> workspace.newTab(root.toString()));
        flush();
        SwingUtilities.invokeAndWait(() -> workspace.filterField().setText("\\.jcl$"));
        flush();
        assertThat(names()).containsExactly("job.jcl");
        SwingUtilities.invokeAndWait(() -> workspace.showBrowseTab(0));
        flush();
        assertThat(workspace.filterField().getText()).isEmpty();
        assertThat(names()).contains("Berichte", "daten.json", "job.jcl", "notiz.txt");
        SwingUtilities.invokeAndWait(() -> workspace.closeBrowseTab(2));
        flush();

        // Nach Neustart sind beide Reiter wieder da; das Kreuz schließt den zweiten.
        SwingUtilities.invokeAndWait(() -> workspace = new FilesWorkspace(
                new FileBrowser(new LocalFileSource(FileSystems.getDefault(), root, List.of()), NOW), editors,
                new LocalDocumentStore()));
        start();
        assertThat(workspace.pillTitles()).containsExactly(top, "Berichte");
        SwingUtilities.invokeAndWait(() -> workspace.closeBrowseTab(1));
        flush();
        assertThat(workspace.pillTitles()).containsExactly(top);
        assertThat(AppState.shared().get(FilesWorkspace.TABS_KEY)).isEmpty();
        assertThat(FilesWorkspace.folderName("C:\\Daten\\Berichte\\")).isEqualTo("Berichte");
    }

    @Test
    void sharePointUsesSavedLoginWithoutAsking() throws Exception {
        java.util.concurrent.CountDownLatch tried = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<String> used = new AtomicReference<>();
        com.aresstack.spaceharvester.deck.files.sharepoint.WebDavLogin login =
                new com.aresstack.spaceharvester.deck.files.sharepoint.WebDavLogin() {
                    @Override
                    public Outcome connect(String uncPath) {
                        return Outcome.credentialsNeeded("Anmeldung nötig");
                    }

                    @Override
                    public Outcome connect(String uncPath, String user, char[] password) {
                        used.set(user + ":" + new String(password));
                        tried.countDown();
                        return Outcome.ok();
                    }
                };
        var saved = com.aresstack.spaceharvester.deck.files.sharepoint.SavedLogins.inMemory();
        saved.save("intranet.example.org", "BEISPIEL\\nutzer", "geheim".toCharArray());
        SwingUtilities.invokeAndWait(() -> {
            workspace = new FilesWorkspace(
                    new FileBrowser(new LocalFileSource(FileSystems.getDefault(), root, List.of()), NOW), editors,
                    new LocalDocumentStore(), null,
                    new com.aresstack.spaceharvester.deck.files.sharepoint.SharePointAccess(login, NOW),
                    List::of, com.aresstack.spaceharvester.deck.access.ServerAccounts.NONE);
            workspace.savedLogins(saved);
        });
        start();

        SwingUtilities.invokeAndWait(() -> workspace.go("\\\\intranet.example.org@SSL\\DavWWWRoot\\sites\\Raum"));

        assertThat(tried.await(10, java.util.concurrent.TimeUnit.SECONDS)).as("gespeicherte Daten probiert").isTrue();
        assertThat(used.get()).isEqualTo("BEISPIEL\\nutzer:geheim");
    }

    @Test
    void rejectedSavedLoginIsNotTriedAgainUntilItWorks() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch rejected = new java.util.concurrent.CountDownLatch(1);
        com.aresstack.spaceharvester.deck.files.sharepoint.WebDavLogin login =
                new com.aresstack.spaceharvester.deck.files.sharepoint.WebDavLogin() {
                    @Override
                    public Outcome connect(String uncPath) {
                        return Outcome.credentialsNeeded("Anmeldung nötig");
                    }

                    @Override
                    public Outcome connect(String uncPath, String user, char[] password) {
                        attempts.incrementAndGet();
                        rejected.countDown();
                        return Outcome.credentialsNeeded("Kennwort abgelaufen");
                    }
                };
        var saved = com.aresstack.spaceharvester.deck.files.sharepoint.SavedLogins.inMemory();
        saved.save("intranet.example.org", "nutzer", "alt".toCharArray());
        java.util.Map<String, String> stored = new java.util.concurrent.ConcurrentHashMap<>();
        var guard = new com.aresstack.spaceharvester.deck.access.LoginGuard(
                new com.aresstack.spaceharvester.deck.access.LoginGuard.Store() {
                    @Override
                    public String get(String key) {
                        return stored.get(key);
                    }

                    @Override
                    public void put(String key, String value) {
                        if (value == null) {
                            stored.remove(key);
                        } else {
                            stored.put(key, value);
                        }
                    }
                });
        SwingUtilities.invokeAndWait(() -> {
            workspace = new FilesWorkspace(
                    new FileBrowser(new LocalFileSource(FileSystems.getDefault(), root, List.of()), NOW), editors,
                    new LocalDocumentStore(), null,
                    new com.aresstack.spaceharvester.deck.files.sharepoint.SharePointAccess(login, NOW),
                    List::of, com.aresstack.spaceharvester.deck.access.ServerAccounts.NONE);
            workspace.savedLogins(saved);
            workspace.loginGuard(guard);
        });
        start();
        String unc = "\\\\intranet.example.org@SSL\\DavWWWRoot\\sites\\Raum";

        SwingUtilities.invokeAndWait(() -> workspace.go(unc));
        assertThat(rejected.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        long until = System.currentTimeMillis() + 5000;
        while (guard.mayTry(FilesWorkspace.savedEntry("nutzer")) && System.currentTimeMillis() < until) {
            flush();
        }
        assertThat(guard.failure(FilesWorkspace.savedEntry("nutzer"))).get()
                .extracting(com.aresstack.spaceharvester.deck.access.LoginGuard.Failure::reason)
                .isEqualTo("Kennwort abgelaufen");

        // Ein weiterer Versuch (Reiterwechsel, neuer Reiter) meldet sich nicht noch einmal mit den alten Daten an.
        SwingUtilities.invokeAndWait(() -> workspace.go(unc));
        for (int i = 0; i < 20; i++) {
            flush();
            Thread.sleep(25);
        }
        assertThat(attempts.get()).as("kein zweiter automatischer Versuch").isEqualTo(1);
        assertThat(stored).isNotEmpty();
        assertThat(String.join("\n", stored.keySet()) + String.join("\n", stored.values()))
                .as("kein Benutzername im Zustand").doesNotContainIgnoringCase("nutzer");
    }

    @Test
    void opensDocumentsAsReadOnlyPreview() throws Exception {
        Files.writeString(root.resolve("bericht.pdf"), "kein echtes PDF");
        com.aresstack.spaceharvester.deck.editor.DocumentStore preview =
                new com.aresstack.spaceharvester.deck.editor.DocumentStore() {
                    @Override
                    public com.aresstack.spaceharvester.deck.editor.EditorDocument load(String location) {
                        return new com.aresstack.spaceharvester.deck.editor.EditorDocument(location, "bericht.pdf",
                                "Text aus dem PDF\n", "UTF-8", "\n", "x");
                    }

                    @Override
                    public com.aresstack.spaceharvester.deck.editor.SaveResult save(
                            com.aresstack.spaceharvester.deck.editor.EditorDocument loaded, String text,
                            boolean overwrite) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public boolean writable() {
                        return false;
                    }
                };
        SwingUtilities.invokeAndWait(() -> workspace = new FilesWorkspace(
                new FileBrowser(new LocalFileSource(FileSystems.getDefault(), root, List.of()), NOW), editors,
                new LocalDocumentStore(), preview,
                new com.aresstack.spaceharvester.deck.files.sharepoint.SharePointAccess(
                        new com.aresstack.spaceharvester.deck.files.sharepoint.windows.NetUseLogin(), NOW),
                List::of, com.aresstack.spaceharvester.deck.access.ServerAccounts.NONE));
        start();
        int row = names().indexOf("bericht.pdf");

        SwingUtilities.invokeAndWait(() -> workspace.openRow(row));
        flush();

        assertThat(workspace.pillTitles()).containsExactly(root.getFileName().toString(), "bericht.pdf (Vorschau)");
        assertThat(workspace.editor(workspace.selected()).text()).isEqualTo("Text aus dem PDF\n");
        assertThat(workspace.selected().writable()).isFalse();
        // Formatiert in den Anhang-Viewern vorn, der Text (wie die KI ihn liest) per Knopf umschaltbar
        DeckFileViewer viewer = workspace.viewer(workspace.selected());
        assertThat(viewer).isNotNull();
        assertThat(viewer.textShown()).isFalse();
        SwingUtilities.invokeAndWait(() -> viewer.showText(true));
        assertThat(viewer.textShown()).isTrue();
    }

    private List<String> labels() throws Exception {
        AtomicReference<List<String>> labels = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            java.util.ArrayList<String> list = new java.util.ArrayList<>();
            for (int row = 0; row < workspace.table().getRowCount(); row++) {
                list.add(workspace.rowLabel(row));
            }
            labels.set(list);
        });
        return labels.get();
    }

    private void start() throws Exception {
        SwingUtilities.invokeAndWait(workspace::start);
        flush();
    }

    private List<String> names() throws Exception {
        AtomicReference<List<String>> names = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            java.util.ArrayList<String> list = new java.util.ArrayList<>();
            for (int row = 0; row < workspace.table().getRowCount(); row++) {
                list.add(((com.aresstack.spaceharvester.deck.files.FileEntry) workspace.table().getValueAt(row, 0))
                        .name());
            }
            names.set(list);
        });
        return names.get();
    }

    /** Wartet, bis alle Meldungen an den EDT abgearbeitet sind (zweimal, weil Meldungen weitere auslösen). */
    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
    }
}
