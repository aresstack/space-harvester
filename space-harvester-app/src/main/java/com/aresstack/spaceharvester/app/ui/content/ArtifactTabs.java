package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifact;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifactStore;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.ClosableTab;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Die Artefakte des offenen Chats als schließbare Reiter in der Mitte (ADR 0045, Abschnitt 5); der Chat bleibt rechts.
 * Über der Arbeitsfläche steht eine Reiterzeile, sobald ein Artefakt offen ist. Ein gewählter Reiter zeigt sein
 * Artefakt statt der Seite; wählt der Benutzer links etwas Neues, kommt die Seite zurück und die Reiter bleiben stehen.
 * Welche Reiter offen waren, merkt sich der Chat ({@link ChatArtifactStore#openTabs}).
 */
final class ArtifactTabs implements ChatPanel.ArtifactHost {

    static final String CLOSE = "Reiter schließen (auch Mittelklick auf den Reiter)";
    private static final String PAGE = "seite";
    private static final String ARTIFACT = "artefakt";

    private final ChatArtifactStore store;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JPanel artifactSlot = new JPanel(new BorderLayout());
    private final Map<String, Tab> tabs = new LinkedHashMap<>();
    private ButtonGroup group = new ButtonGroup();
    private final Consumer<ChatArtifactStore.Event> titles = this::retitle;
    private Path folder;
    private String chatId;
    private volatile String selected;

    private record Tab(ClosableTab pill, ArtifactView view) {
    }

    ArtifactTabs(ChatArtifactStore store, JComponent page) {
        this.store = store;
        strip.setOpaque(true);
        strip.setBackground(Comic.SURFACE);
        strip.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE));
        strip.setVisible(false);
        artifactSlot.setOpaque(false);
        center.setOpaque(false);
        center.add(page, PAGE);
        center.add(artifactSlot, ARTIFACT);
        root.setOpaque(false);
        root.add(strip, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        store.addListener(titles);
    }

    JComponent component() {
        return root;
    }

    @Override
    public void chat(Path folder, String chatId) {
        if (Objects.equals(this.folder, folder) && Objects.equals(this.chatId, chatId)) {
            return;
        }
        this.folder = folder;
        this.chatId = chatId;
        boolean wasShowing = selected != null;
        tabs.values().forEach(tab -> tab.view().dispose());
        tabs.clear();
        group = new ButtonGroup();
        strip.removeAll();
        selected = null;
        artifactSlot.removeAll();
        if (folder != null && chatId != null) {
            for (String id : store.openTabs(folder, chatId)) {
                add(id);
            }
        }
        refreshStrip();
        if (wasShowing) {
            showPage();
        }
    }

    @Override
    public void open(String artifactId) {
        if (chatId == null || artifactId == null) {
            return;
        }
        String id = artifactId.strip().toUpperCase(java.util.Locale.ROOT);
        if (!tabs.containsKey(id) && add(id) == null) {
            return;
        }
        remember();
        select(id);
    }

    @Override
    public String focus() {
        return selected;
    }

    /** Die Seite kommt zurück (der Benutzer hat links etwas gewählt); die Reiter bleiben stehen. */
    void showPage() {
        selected = null;
        group.clearSelection();
        artifactSlot.removeAll();
        cards.show(center, PAGE);
    }

    /** Für Tests: die offenen Reiter. */
    List<String> openIds() {
        return new ArrayList<>(tabs.keySet());
    }

    /** Für Tests und den Export: die Ansicht des gewählten Artefakts, sonst {@code null}. */
    ArtifactView selectedView() {
        Tab tab = selected == null ? null : tabs.get(selected);
        return tab == null ? null : tab.view();
    }

    private Tab add(String id) {
        ChatArtifact artifact = store.read(folder, chatId, id);
        if (artifact == null) {
            return null;
        }
        ClosableTab pill = new ClosableTab(artifact.kind() == ChatArtifact.Kind.GRAPHIC ? ComposerIcons.picture()
                : ComposerIcons.document(), artifact.title(), artifact.title(), CLOSE, () -> close(artifact.id()));
        pill.addActionListener(event -> select(artifact.id()));
        Tab tab = new Tab(pill, new ArtifactView(store, folder, chatId, artifact.id()));
        tabs.put(artifact.id(), tab);
        group.add(pill);
        strip.add(pill);
        refreshStrip();
        return tab;
    }

    private void select(String id) {
        Tab tab = tabs.get(id);
        if (tab == null) {
            return;
        }
        selected = id;
        tab.pill().setSelected(true);
        artifactSlot.removeAll();
        artifactSlot.add(tab.view(), BorderLayout.CENTER);
        artifactSlot.revalidate();
        artifactSlot.repaint();
        cards.show(center, ARTIFACT);
    }

    private void close(String id) {
        Tab tab = tabs.remove(id);
        if (tab == null) {
            return;
        }
        tab.view().dispose();
        group.remove(tab.pill());
        strip.remove(tab.pill());
        remember();
        refreshStrip();
        if (id.equals(selected)) {
            showPage();
        }
    }

    private void remember() {
        if (folder != null && chatId != null) {
            store.rememberTabs(folder, chatId, new ArrayList<>(tabs.keySet()));
        }
    }

    private void refreshStrip() {
        strip.setVisible(!tabs.isEmpty());
        strip.revalidate();
        strip.repaint();
    }

    /** Ein umbenanntes Artefakt bekommt seinen neuen Titel auch am Reiter. */
    private void retitle(ChatArtifactStore.Event event) {
        Runnable update = () -> {
            if (!Objects.equals(folder, event.folder()) || !Objects.equals(chatId, event.chatId())) {
                return;
            }
            Tab tab = tabs.get(event.artifact().id());
            if (tab != null && !tab.pill().getText().equals(event.artifact().title())) {
                tab.pill().setText(event.artifact().title());
                tab.pill().setToolTipText(event.artifact().title());
                refreshStrip();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }
}
