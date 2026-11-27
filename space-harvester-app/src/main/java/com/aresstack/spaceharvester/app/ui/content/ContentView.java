package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.SignInSlot;
import com.aresstack.spaceharvester.app.ui.PillTabs;
import com.aresstack.spaceharvester.app.ui.View;
import com.aresstack.spaceharvester.application.changelog.ChangeLevel;
import com.aresstack.spaceharvester.application.passages.PassageId;
import com.aresstack.spaceharvester.application.sources.HarvestSource;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Objects;

/**
 * Inhalte: die Leseansicht. Welche Seite sie zeigt, entscheidet der Explorer links (oder die Suche, ein Auftrag, eine
 * Adresse); eine eigene Quellen-, Space- oder Seitenauswahl hat sie nicht. Oben steht, wo die Seite liegt
 * (Wissensquelle › Space).
 */
public final class ContentView implements View {

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout());
    private final LocationBar crumbs = new LocationBar();
    private JPanel historyHeader;
    private boolean searchFront;
    /** Pfad der gezeigten Seite, sobald der Core ihn geliefert hat: die Vorfahren in der Bereichsanzeige. */
    private com.aresstack.spaceharvester.application.navigation.PageNavigation.Position position;
    private java.util.function.Consumer<com.aresstack.spaceharvester.application.navigation.PageNavigation.Position>
            positionListener = shown -> { };
    /** Markiert Quelle ({@code spaceKey} {@code null}) oder Space im Baum; die Auswahl dort bestimmt die Mitte. */
    private java.util.function.BiConsumer<String, String> locate = (source, space) -> { };
    /** Wählt eine Seite im Baum, wenn Zurück/Vor sie wieder zeigt. */
    private java.util.function.BiConsumer<String, String> locatePage = (source, pageId) -> { };
    private final PageDetailPanel detail;
    /** Stand des gezeigten Reiters (etwa „50 Knoten, 51 Kanten“) links in der Statuszeile des Hauptfensters. */
    private final com.aresstack.spaceharvester.app.ui.controls.StatusLabel statusInfo =
            new com.aresstack.spaceharvester.app.ui.controls.StatusLabel();
    private final JPanel body = new JPanel(new BorderLayout());
    /**
     * Die Mitte folgt der Wippe links (ADR 0047 §5): unter „Wissensquellen“ die Seitenansicht, unter „Artefakte“ die
     * geöffneten Artefakte. Beim Zurückschalten steht die Seite, wie man sie verlassen hat.
     */
    private final java.awt.CardLayout middle = new java.awt.CardLayout();
    private final JPanel middleDeck = new JPanel(middle);
    private static final String PAGE_MIDDLE = "seite";
    private static final String ARTIFACT_MIDDLE = "artefakte";
    static final String NO_ARTIFACT = "Kein Artefakt geöffnet: links eines wählen";
    /** Die untere Leiste: unter „Artefakte“ die Formate des Artefakts vorn, sonst die der Seitenansicht. */
    private final JPanel tools = new JPanel(new BorderLayout());
    /** Ob die Werkzeuge vorn die Zeile füllen ({@link View#TOOLS_FILL}), gespiegelt auf die Hülle, die die Suchleiste sieht. */
    private final java.beans.PropertyChangeListener fillMirror = event -> mirrorFill((JComponent) event.getSource());
    private boolean artifactsFront;
    private JPanel north;
    /** Wünscht die Artefakt-Mitte (Klick auf einen Verweis im Chat): links auf „Artefakte“ schalten. */
    private Runnable artifactsWanted = () -> { };
    /** Wünscht die Seite (ein Reiter der Seitenansicht wurde gewählt): links auf „Wissensquellen“ schalten. */
    private Runnable pageWanted = () -> { };
    /** Die Export-Reiter im Reiter „Export“; Artefakte stehen dort nicht mehr. */
    private final ArtifactTabs exportTabs;
    /** Die Einmalcode-Abfrage der gezeigten Wissensquelle (ADR 0018), etwa für Anhänge und Bilder. */
    private final SignInSlot signIn;
    /** Die geöffneten Artefakte als Reiter in der Artefakt-Mitte (ADR 0047 §5). */
    private final ArtifactTabs artifactTabs;

    private String sourceId;
    /** „Wissensquellen“ im Explorer gewählt: die Bereichsanzeige nennt keine Quelle (Architekt 17.11.2026). */
    private boolean allSources;
    private String spaceKey;
    /** Der gewählte Favoriten-Ordner (ADR 0034), sonst {@code null}. */
    private ExportPanel.Selection.Collection folder;
    /**
     * Die Stellung von „mit Unterseiten“ am Ordner (ADR 0034 §6): Vorgabe an (der oberste Ordner enthält alles
     * darunter), eine Stellung für alle Ordner, gemerkt nur für die Sitzung, wie an der Seite.
     */
    private boolean folderSubfolders = true;

    public ContentView(AppContext context) {
        this.context = context;
        this.detail = new PageDetailPanel(context);
        detail.onShown(this::pageShown);
        detail.onStatusNote(statusInfo::show);
        this.signIn = new SignInSlot(context, requests -> sourceId == null ? java.util.Optional.empty()
                : requests.pendingFor(sourceId));
        // Nach dem Einmalcode lädt die gezeigte Seite neu, damit Bilder und Anhänge ohne Sperre erscheinen.
        signIn.onAccepted(detail::reloadShown);
        root.setBackground(Comic.SURFACE);

        detail.onPosition(shown -> {
            position = shown;
            renderCrumbs();
            positionListener.accept(shown);
        });
        // Zurück/Vor (im Viewer-Header, Maustasten): Bereichsanzeige und Baum folgen dem wieder gezeigten Ort.
        detail.onNavigated(place -> {
            allSources = false;
            sourceId = place.sourceId();
            // Ein Ordner steht als Ebene im Verlauf: Space und Wurzel des Teilbaums (ChangeLevel).
            ChangeLevel level = ChangeLevel.of(place.page() ? null : place.spaceKey());
            spaceKey = level.spaceKey();
            renderCrumbs();
            if (place.page()) {
                locatePage.accept(place.sourceId(), place.pageId());
            } else if (level.subtree()) {
                locatePage.accept(place.sourceId(), level.rootPageId());
            } else {
                locate.accept(place.sourceId(), place.spaceKey());
            }
        });

        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setBackground(Comic.SURFACE);
        top.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));
        // Eine Zeile statt zwei (Angelo, 16.11.2026): links, wo die Seite liegt, mittig ihre Überschrift, rechts Stern
        // und das Dreieck mit den weiteren Aktionen. Die Überschrift kürzt sich mit „…“, wenn der Platz nicht reicht.
        historyHeader = locationHeader();
        top.add(historyHeader, BorderLayout.WEST);
        top.add(detail.headerTitle(), BorderLayout.CENTER);
        top.add(detail.headerActions(), BorderLayout.EAST);

        north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(top, BorderLayout.NORTH);
        signIn.setBorder(BorderFactory.createEmptyBorder(8, 12, 0, 12));
        north.add(signIn, BorderLayout.CENTER);
        root.add(north, BorderLayout.NORTH);
        body.setOpaque(false);
        root.add(body, BorderLayout.CENTER);
        middleDeck.setOpaque(false);
        body.add(middleDeck, BorderLayout.CENTER);
        middleDeck.add(detail, PAGE_MIDDLE);
        java.util.function.Supplier<java.nio.file.Path> output =
                () -> context.settings() == null ? null : context.settings().outputDirectory();
        java.util.function.Consumer<String> status =
                text -> statusInfo.show(com.aresstack.spaceharvester.app.ui.controls.StatusNote.plain(text));
        // Unter „Export“ nur noch Exporte (Entwurf UX 27.11.2026); derselbe Baustein ohne Chat.
        exportTabs = new ArtifactTabs(detail.chat().artifactStore(), detail.export(), output, status,
                () -> new ExportPanel(context));
        detail.artifactTabs(exportTabs);
        // Die Artefakt-Reiter ziehen aus „Export“ in die Artefakt-Mitte um (ADR 0047 §5).
        JLabel none = Comic.muted(NO_ARTIFACT);
        none.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        artifactTabs = new ArtifactTabs(detail.chat().artifactStore(), none, output, status, null);
        artifactTabs.front(false);
        artifactTabs.reveal(() -> artifactsWanted.run());
        artifactTabs.onSelectionChanged(this::refreshTools);
        middleDeck.add(artifactTabs.component(), ARTIFACT_MIDDLE);
        detail.chat().artifactHost(artifactTabs);
        tools.setOpaque(false);
        refreshTools();
        context.onChange(ignored -> renderCrumbs());
        renderCrumbs();
        // Die Checkbox am Ordner löst ihn mit der neuen Stellung auf; eine Änderung am Ordner gilt sofort, also auch
        // ab der nächsten Frage im Chat (ADR 0034 §3, Beobachter, kein Zeitgeber).
        detail.onFolderSubfolders(on -> {
            folderSubfolders = on;
            refreshFolder();
        });
        context.favorites().onChange(() -> {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                refreshFolder();
            } else {
                javax.swing.SwingUtilities.invokeLater(this::refreshFolder);
            }
        });
    }

    /**
     * Wählt einen Favoriten-Ordner (ADR 0034): löst ihn mit der gemerkten Stellung von „mit Unterseiten“ auf (aus = die
     * direkten Favoriten, an = samt Unterordnern) und wendet die Auswahl an wie eine Auswahl im Baum.
     */
    public void folder(String folderId, String title) {
        selection(ExportPanel.Selection.Collection.of(context.favorites(), folderId, title, folderSubfolders));
    }

    /** Löst den gewählten Ordner neu auf; gleiche Wurzeln und Stellung lassen die Auswahl, wie sie ist. */
    private void refreshFolder() {
        if (folder == null) {
            return;
        }
        ExportPanel.Selection.Collection next = folder.withSubfolders(context.favorites(), folderSubfolders);
        if (!next.equals(folder)) {
            selection(next);
        }
    }

    /** Der Speicher der Artefakte, derselbe wie der des Chats (für die Liste „Artefakte“ links, ADR 0047 §5). */
    public com.aresstack.spaceharvester.app.chat.artifact.ChatArtifactStore artifactStore() {
        return detail.chat().artifactStore();
    }

    /** Öffnet ein Artefakt des Projekts in der Mitte (Klick in der Liste „Artefakte“ links). */
    public void openArtifact(String artifactId) {
        artifactTabs.open(artifactId);
    }

    /**
     * Die Mitte folgt der Wippe links (ADR 0047 §5): {@code true} zeigt die geöffneten Artefakte, {@code false} die
     * Seitenansicht, wie man sie verlassen hat.
     */
    public void showArtifacts(boolean shown) {
        artifactsFront = shown;
        middle.show(middleDeck, shown ? ARTIFACT_MIDDLE : PAGE_MIDDLE);
        north.setVisible(!shown);
        artifactTabs.front(shown);
        detail.tabs().showSelection(!shown);
        refreshTools();
        root.revalidate();
        root.repaint();
    }

    /**
     * „Ausgeben“ aus der Liste „Artefakte“ links: exportiert ein Artefakt des aktiven Projekts in den Ausgabeordner;
     * {@code format} {@code null} heißt SVG (Grafik).
     */
    public void exportArtifact(String artifactId, com.aresstack.spaceharvester.app.chat.ChatExport.Format format) {
        String projectId = context.projects().active() == null ? null : context.projects().active().id();
        com.aresstack.spaceharvester.app.chat.artifact.ChatArtifactStore store = artifactStore();
        com.aresstack.spaceharvester.app.chat.artifact.ChatArtifact artifact = store.readInProject(projectId,
                artifactId);
        if (artifact != null) {
            artifactTabs.export(artifact, format, id -> store.readInProject(projectId, id));
        }
    }

    /** Ob die Artefakt-Mitte vorn steht. */
    public boolean artifactsShown() {
        return artifactsFront;
    }

    /** Wer links auf „Artefakte“ schaltet, wenn ein Verweis im Chat ein Artefakt öffnet. */
    public void onArtifactsWanted(Runnable listener) {
        artifactsWanted = listener == null ? () -> { } : listener;
    }

    /** Wer links auf „Wissensquellen“ schaltet, wenn unter „Artefakte“ ein Reiter der Seitenansicht gewählt wird. */
    public void onPageWanted(Runnable listener) {
        pageWanted = listener == null ? () -> { } : listener;
    }

    /** Wie viele Artefakte die KI geändert hat, seit man zuletzt unter „Artefakte“ war (Chip an der Wippe). */
    public void onUnseenArtifacts(java.util.function.IntConsumer listener) {
        artifactTabs.onUnseen(listener);
    }

    /** Die Formate des Artefakts vorn unter „Artefakte“, sonst die Werkzeuge der Seitenansicht. */
    private void refreshTools() {
        JComponent shown = artifactsFront ? artifactTabs.footBar() : detail.footTools();
        if (tools.getComponentCount() == 1 && tools.getComponent(0) == shown) {
            return;
        }
        if (tools.getComponentCount() == 1 && tools.getComponent(0) instanceof JComponent old) {
            old.removePropertyChangeListener(View.TOOLS_FILL, fillMirror);
        }
        tools.removeAll();
        if (shown != null) {
            tools.add(shown, BorderLayout.CENTER);
            shown.addPropertyChangeListener(View.TOOLS_FILL, fillMirror);
        }
        mirrorFill(shown);
    }

    /** Die Suchleiste fragt nur die Hülle, ob die Werkzeuge die Zeile füllen; sie folgt dem gezeigten Inhalt. */
    private void mirrorFill(JComponent shown) {
        tools.putClientProperty(View.TOOLS_FILL, shown != null && Boolean.TRUE.equals(shown.getClientProperty(
                View.TOOLS_FILL)));
        tools.revalidate();
        tools.repaint();
    }

    /** Wer ausdrücklich eine Seite oder Quelle öffnet, während die Artefakte vorn stehen, holt die Seite zurück. */
    private void pageRequested() {
        if (artifactsFront) {
            pageWanted.run();
        }
    }

    /** Für Tests: die Artefakt-Mitte. */
    ArtifactTabs artifactTabs() {
        return artifactTabs;
    }

    @Override
    public JComponent component() {
        return root;
    }

    /** Die Bereichsanzeige bei „Wissensquellen“: kein Verweis, keine Quelle. */
    static final String ALL_SOURCES = "Wissensquellen";

    /** Ein Space wurde gewählt (z. B. „Inhalte öffnen“ eines Auftrags): ohne Seite, die wählt man im Explorer. */
    public void openSpace(String sourceId, String spaceKey) {
        pageRequested();
        allSources = false;
        // Ein Space hat dieselbe Seite wie die Quelle, als Ausschnitt seiner Updates (ADR-0030-Nachtrag, TZ5).
        if (!detail.showsLevel(sourceId, spaceKey)) {
            detail.showSource(sourceId, spaceKey, spaceKey);
        }
        this.sourceId = sourceId;
        this.spaceKey = spaceKey;
        renderCrumbs();
    }

    /** Die Quellenseite unter „Leseansicht“ (Doppelklick auf eine Quelle im Explorer, ADR 0030). */
    /** Die Quelle, deren Updates gerade zu sehen sind, sonst {@code null}. */
    public String shownSource() {
        // Nur die ganze Quelle: „Nach dem Update Änderungen anzeigen“ öffnet die Quelle, nicht einen Space.
        return detail.showsSource() && detail.sourceView().spaceKey() == null ? detail.sourceView().sourceId() : null;
    }

    public void openSource(String sourceId, String title) {
        pageRequested();
        allSources = false;
        if (!detail.showsLevel(sourceId, null)) {
            detail.showSource(sourceId, title);
        }
        this.sourceId = sourceId;
        this.spaceKey = null;
        renderCrumbs();
        detail.showReading();
    }

    /** Öffnet eine Seite (aus dem Explorer, der Suche, einem Auftrag). */
    public void openPage(String sourceId, String pageId) {
        pageRequested();
        if (!Objects.equals(sourceId, this.sourceId)) {
            this.spaceKey = null;
        }
        this.sourceId = sourceId;
        renderCrumbs();
        detail.load(sourceId, pageId, false);
    }

    /**
     * Öffnet einen Knoten ohne eigenen Inhalt (einen Ordner) aus dem Explorer: „Änderungen | Bereich“ für den Teilbaum
     * unter ihm, nie die vorher gezeigte Seite (Angelo 26.11.2026); schon gezeigt, bleibt er.
     */
    public void openContainer(String sourceId, String spaceKey, String pageId, String title) {
        pageRequested();
        allSources = false;
        String space = spaceKey == null || spaceKey.isBlank() ? null : spaceKey;
        if (!detail.showsLevel(sourceId, ChangeLevel.key(space, pageId))) {
            detail.showContainer(sourceId, space, pageId, title == null || title.isBlank() ? pageId : title);
        }
        this.sourceId = sourceId;
        this.spaceKey = space;
        renderCrumbs();
    }

    /**
     * Öffnet einen Absatz über seine stabile Passage-ID (#452): seine Seite im zuletzt gewählten Reiter, der Absatz markiert.
     * {@code sourceId} ist die Quelle des Aufrufers (z. B. eines Suchtreffers); maßgeblich ist die ID selbst.
     */
    public void openProgram(String sourceId, String pageId, String rawSha256, int line) {
        pageRequested();
        if (!Objects.equals(sourceId, this.sourceId)) {
            this.spaceKey = null;
        }
        this.sourceId = sourceId;
        renderCrumbs();
        detail.openProgram(sourceId, pageId, rawSha256, line);
    }

    public void openPassage(String sourceId, String passageId) {
        pageRequested();
        String source = PassageId.looksLike(passageId) ? PassageId.parse(passageId).sourceId() : sourceId;
        if (!Objects.equals(source, this.sourceId)) {
            this.spaceKey = null;
        }
        this.sourceId = source;
        renderCrumbs();
        detail.openPassage(passageId);
    }

    /** Setzt den Inhalt des Unterreiters „Analysen → Aussagen“ (#499), siehe {@link PageDetailPanel#statementsTab}. */
    public void statementsTab(JComponent component) {
        detail.statementsTab(component);
    }

    /** Setzt den Inhalt des Unterreiters „Analysen → Graph“ (#499), siehe {@link PageDetailPanel#knowledgeGraphTab}. */
    public void knowledgeGraphTab(JComponent component) {
        detail.knowledgeGraphTab(component);
    }

    /** Meldet den Pfad jeder angezeigten Seite, etwa damit der Explorer sie zeigt. */
    public void onPosition(java.util.function.Consumer<
            com.aresstack.spaceharvester.application.navigation.PageNavigation.Position> listener) {
        positionListener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Ein Klick auf Quelle oder Space in der Bereichsanzeige: {@code listener} markiert den Knoten im Baum
     * ({@code spaceKey} {@code null} für die Quelle).
     */
    public void onLocate(java.util.function.BiConsumer<String, String> listener) {
        locate = Objects.requireNonNull(listener, "listener");
    }

    /** Zurück/Vor zeigt eine Seite wieder: {@code listener} wählt sie im Baum (Quelle, Seiten-ID). */
    public void onLocatePage(java.util.function.BiConsumer<String, String> listener) {
        locatePage = Objects.requireNonNull(listener, "listener");
    }

    /** Nach dem Laden kennt die Seite ihren Space. */
    private void pageShown(String sourceId, String spaceKey) {
        // Wählt der Benutzer links etwas, kommt der Export der Auswahl zurück; offene Export-Reiter bleiben stehen.
        exportTabs.showPage();
        // Öffnet sich eine Seite (etwa über einen Quellen-Link im Chat), während die Artefakte vorn stehen, kommt die
        // Seite nach vorn und links „Wissensquellen“.
        pageRequested();
        this.sourceId = sourceId;
        this.spaceKey = spaceKey == null || spaceKey.isBlank() ? null : spaceKey;
        renderCrumbs();
    }

    /** Die Wissensquelle der gezeigten Seite; ohne Seite die einzige, wenn es nur eine gibt. */
    private String currentSource() {
        if (sourceId != null) {
            return sourceId;
        }
        List<HarvestSource> sources = sources();
        return sources.size() == 1 ? sources.get(0).id() : null;
    }

    private void renderCrumbs() {
        signIn.refresh();
        if (allSources) {
            crumbs.show(List.of(new LocationBar.Part(ALL_SOURCES, null, null)));
            return;
        }
        String source = currentSource();
        if (source == null) {
            crumbs.show(List.of(new LocationBar.Part(sources().isEmpty() ? "Keine Wissensquelle"
                    : "Seite im Explorer wählen", null, null)));
            return;
        }
        List<LocationBar.Part> parts = new java.util.ArrayList<>();
        parts.add(new LocationBar.Part(title(source), "Wissensquelle im Baum zeigen",
                () -> locate.accept(source, null)));
        String space = spaceKey;
        if (space != null) {
            parts.add(new LocationBar.Part(space, "Space im Baum zeigen", () -> locate.accept(source, space)));
        }
        // Die Vorfahren der gezeigten Seite, sobald ihr Pfad da ist und zur gezeigten Quelle gehört.
        if (position != null && source.equals(position.sourceId()) && Objects.equals(space, position.spaceKey())) {
            for (HarvestSource.PageNode ancestor : position.ancestors()) {
                String pageId = ancestor.pageId();
                if (!ancestor.kind().hasOwnContent()) {
                    parts.add(new LocationBar.Part(Breadcrumbs.title(ancestor), "Ordner im Baum zeigen", () -> locatePage.accept(source, pageId)));
                } else {
                    parts.add(new LocationBar.Part(Breadcrumbs.title(ancestor), "Seite öffnen und im Baum zeigen",
                            () -> open(source, pageId)));
                }
            }
        }
        crumbs.show(parts);
    }

    /** Öffnet eine Seite aus der Bereichsanzeige; über den Navigator zeigt der Baum sie aufgeklappt und gewählt. */
    private void open(String source, String pageId) {
        AppContext.Navigator navigator = context.navigator();
        if (navigator != null) {
            navigator.openPage(source, pageId);
        } else {
            openPage(source, pageId);
        }
    }

    private String title(String id) {
        for (HarvestSource source : sources()) {
            if (source.id().equals(id)) {
                return source.title() == null || source.title().isBlank() ? id : source.title();
            }
        }
        return id;
    }

    private List<HarvestSource> sources() {
        AppRuntime runtime = context.runtime();
        return runtime == null ? List.of() : runtime.workspace().sources();
    }

    String chosenSource() {
        return sourceId;
    }

    String chosenSpace() {
        return spaceKey;
    }

    String crumbs() {
        return crumbs.text();
    }

    LocationBar locationBar() {
        return crumbs;
    }

    @Override
    public JComponent statusInfo() {
        return statusInfo;
    }

    /**
     * Die Reiter der Seitenansicht als Symbolknöpfe für die Icon-Leiste des Hauptfensters; sie schalten die Mitte um
     * (Angelo, 13.11.2026).
     */
    public JComponent tabBar() {
        JComponent bar = detail.tabIcons();
        // Ein Reiter der Seitenansicht unter „Artefakte“ holt die Seite zurück und schaltet links um.
        for (String name : detail.tabs().names()) {
            detail.tabs().button(name).addActionListener(event -> {
                if (artifactsFront) {
                    pageWanted.run();
                }
            });
        }
        return bar;
    }

    /**
     * Die Werkzeuge der Seitenansicht in der unteren Leiste, je nach Reiter ({@link PageDetailPanel#footTools()}):
     * in der Leseansicht einer Datei „Speichern“ und „Öffnen“
     * neben der Lupe samt „Typ · Größe“ rechts, im Text eines Videos der Transkript-Knopf (Angelo, 19.11.2026).
     */
    @Override
    public JComponent tools() {
        return tools;
    }

    /**
     * Der Chat als Seitenleiste für das Hauptfenster (Angelo, 21.11.2026): er steht neben der Arbeitsfläche und bleibt
     * in jedem Bereich stehen; sein Wissen ist weiter die Auswahl links.
     */
    public ChatSidebar chatSidebar() {
        if (chatSidebar == null) {
            chatSidebar = new ChatSidebar(detail.chat());
        }
        return chatSidebar;
    }

    private ChatSidebar chatSidebar;

    /** Die Analysen als Seitenleiste rechts; das Hauptfenster stellt sie zwischen Chat und Aufträge. */
    public AnalysesSidebar analysesSidebar() {
        if (analysesSidebar == null) {
            analysesSidebar = new AnalysesSidebar(detail);
        }
        return analysesSidebar;
    }

    private AnalysesSidebar analysesSidebar;

    /** Ob die Reiter ihre Auswahl zeigen: nur, solange die Mitte die Seitenansicht zeigt ({@link PillTabs#showSelection}). */
    public void tabsActive(boolean active) {
        detail.tabs().showSelection(active && !artifactsFront);
    }

    /**
     * Die Auswahl links im Explorer (Quelle, Space oder Seite samt Unterseiten) bestimmt die Reiter „Export“ und
     * „Chat“ (Angelo, 13.11.2026); {@code null} ohne Auswahl.
     */
    public void selection(ExportPanel.Selection selection) {
        exportTabs.showPage();
        detail.export().selection(selection);
        detail.chat().onResolved(resolved -> detail.graph().within(resolved == null ? null : resolved.pages()));
        detail.chat().selection(selection);
        // Die Analysen der Auswahl stehen in der Seitenleiste auch neben der Suche: Sie folgen der Auswahl wie Chat
        // und Export. Welche Ebene sie zeigen (Seite oder Auswahl), bleibt bei der offenen Seite in der Mitte.
        detail.standing().selection(selection);
        detail.overviewSelection(selection);
        if (searchFront) {
            return;
        }
        folder = selection instanceof ExportPanel.Selection.Collection collection ? collection : null;
        // Eine Seite zeigt unter „Analysen“ ihre eigenen Analysen, jede weitere Ebene den Stand der Auswahl.
        detail.wide(selection == null || selection.global() || !selection.page());
        // Die globale Auswahl (alle Wissensquellen) lässt Brotkrumen und Einmalcode-Abfrage, wie sie sind; die Mitte
        // zeigt leer statt einer alten Seite und unter Eigenschaften die Gesamtsicht (Angelo 17.11.2026).
        if (selection != null && selection.global()) {
            allSources = true;
            if (context.runtime() != null) {
                List<String> ids = context.runtime().workspace().sources().stream().map(HarvestSource::id).toList();
                // Hat sich die Quellenliste geändert, baut sich die Gesamtsicht neu auf.
                if (!detail.showsAllSources(ids)) {
                    detail.showAllSources(ids, selection.title());
                }
            }
            renderCrumbs();
        } else if (selection != null) {
            allSources = false;
            // Quelle und Space der Auswahl: oben die Brotkrumen und die Einmalcode-Abfrage, auch ohne geöffnete Seite
            // (etwa ein Download aus „Export“ eines nur gewählten Spaces fragt den Code hier ab, nicht erst in Aufträge).
            // Eine andere Quelle, oder eine Quelle bzw. ein Space statt einer Seite: Die Seitenreiter (Leseansicht bis
            // Erweitert) haben dafür keinen Inhalt und zeigen leer statt der vorher geöffneten Seite; Export und Chat
            // folgen der Auswahl (Angelo, 16.11.2026).
            String space = selection.spaceKey() == null || selection.spaceKey().isBlank() ? null : selection.spaceKey();
            if (selection instanceof ExportPanel.Selection.Collection collection) {
                // Ein Favoriten-Ordner: der Überblick über seine Favoriten (ADR 0034 §7, Paket 5c); eine Änderung am
                // Ordner zeigt nur „veraltet“, nie einen KI-Lauf von selbst.
                if (!detail.showsFolder(collection)) {
                    detail.showFolder(collection);
                }
            } else if (!selection.page()) {
                // Die Wurzel einer Quelle oder ein Space: die Quellenseite mit den Updates dieser Ebene (ADR 0030,
                // Nachtrag TZ5); schon gezeigt, bleibt sie.
                if (!detail.showsLevel(selection.sourceId(), space)) {
                    detail.showSource(selection.sourceId(), space, selection.title());
                }
            } else if (!Objects.equals(selection.sourceId(), sourceId)
                    && !detail.showsPage(selection.sourceId(), selection.pageId())) {
                // Die Seite selbst öffnet der Navigator gleich; wird sie schon gezeigt (Zurück/Vor), bleibt sie.
                detail.clear();
            }
            sourceId = selection.sourceId();
            spaceKey = selection.spaceKey() == null || selection.spaceKey().isBlank() ? null : selection.spaceKey();
            renderCrumbs();
        }
    }

    /** Zeigt den Reiter „Export“ für die Auswahl links, etwa aus „Inhalte öffnen“ eines Auftrags. */
    public void showExport() {
        pageRequested();
        detail.showExport();
    }

    /** Keep the existing history button immediately before the breadcrumb, within its original width budget. */
    private JPanel locationHeader() {
        JComponent navigation = detail.navigation();
        JPanel header = new JPanel(new BorderLayout(6, 0)) {
            @Override
            public java.awt.Dimension getPreferredSize() {
                int available = getParent() == null ? 0 : getParent().getWidth();
                java.awt.Dimension button = navigation.getPreferredSize();
                int reserved = button.width + 6;
                java.awt.Dimension path = crumbs.preferredSizeForWidth(available, reserved);
                return new java.awt.Dimension(reserved + path.width, Math.max(button.height, path.height));
            }
        };
        header.setOpaque(false);
        header.add(navigation, BorderLayout.WEST);
        header.add(crumbs, BorderLayout.CENTER);
        return header;
    }

    /** Expose the one document-history control for placement in the active view header. */
    public JComponent documentNavigation() {
        return detail.navigation();
    }

    public void restoreDocumentNavigation() {
        JComponent navigation = documentNavigation();
        if (navigation.getParent() != historyHeader) {
            historyHeader.add(navigation, BorderLayout.WEST);
            historyHeader.revalidate();
            historyHeader.repaint();
        }
    }

    /** Keep search filters separate from the displayed document and its navigation history. */
    public void searchFront(boolean active) {
        searchFront = active;
        detail.searchFront(active);
    }

    public void useWorkspaceHistory(com.aresstack.spaceharvester.app.ui.controls.BackButton.Navigator navigator,
                                    java.util.function.Consumer<PageDetailPanel.Place> listener) {
        detail.useWorkspaceHistory(navigator, listener);
    }

    public void refreshHistoryNavigation() {
        detail.refreshHistoryNavigation();
    }

    public java.util.Optional<PageDetailPanel.Place> currentHistoryPlace() {
        return detail.currentHistoryPlace();
    }

    public void restoreHistoryPlace(PageDetailPanel.Place place) {
        detail.restoreHistoryPlace(place);
    }

    public void beforeHistoryNavigation(Runnable listener) {
        detail.beforeHistoryNavigation(listener);
    }

    PageDetailPanel detail() {
        return detail;
    }
}
