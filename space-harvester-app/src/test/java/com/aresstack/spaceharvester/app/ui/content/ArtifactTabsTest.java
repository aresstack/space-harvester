package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.chat.ChatNode;
import com.aresstack.spaceharvester.app.chat.ChatRecord;
import com.aresstack.spaceharvester.app.chat.ChatStore;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifact;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Artefakte öffnen sich als Reiter in der Mitte, heben die letzte Änderung hervor und bleiben je Chat gemerkt. */
class ArtifactTabsTest {

    @TempDir
    Path home;

    @Test
    void anArtifactIsDeletedAfterAskingAndComesBackFromTheTrash() throws Exception {
        // ADR 0047: Löschen am Artefakt nach „wirklich löschen?“; bis zum nächsten Start wiederherstellbar.
        ChatStore chats = new ChatStore(() -> home.resolve("chats"));
        ChatNode node = new ChatNode("wiki", null, null);
        ChatRecord chat = ChatRecord.fresh(1).plus(ChatRecord.Message.user("Schreib", 1), 1);
        chats.save(node, chat);
        Path folder = chats.folder(node);
        ChatArtifactStore store = new ChatArtifactStore(chats);
        store.createDocument(folder, chat.id(), "Gartenfibel", "# Gartenfibel", ChatArtifact.Origin.AI);
        ArtifactTabs[] tabs = new ArtifactTabs[1];
        java.util.List<String> asked = new java.util.ArrayList<>();
        boolean[] answer = {false};
        SwingUtilities.invokeAndWait(() -> {
            tabs[0] = new ArtifactTabs(store, new JLabel("Seite"));
            tabs[0].confirm = (title, question) -> {
                asked.add(question);
                return answer[0];
            };
            tabs[0].chat(folder, chat.id());
            tabs[0].open("D1");
            tabs[0].deleteSelected();
        });
        assertThat(asked).containsExactly(ArtifactTabs.deleteQuestion("Gartenfibel"));
        assertThat(tabs[0].artifacts()).as("Nein lässt es stehen").hasSize(1);

        answer[0] = true;
        SwingUtilities.invokeAndWait(() -> tabs[0].deleteSelected());
        assertThat(tabs[0].artifacts()).isEmpty();
        assertThat(tabs[0].openIds()).as("der Reiter geht zu").isEmpty();
        assertThat(tabs[0].deletedArtifacts()).extracting(ChatArtifact::id).containsExactly("D1");
        assertThat(store.createDocument(folder, chat.id(), "Brief", "", ChatArtifact.Origin.AI).id())
                .as("die Kennung im Papierkorb wird nicht neu vergeben").isEqualTo("D2");

        SwingUtilities.invokeAndWait(() -> tabs[0].restore("D1"));
        assertThat(tabs[0].artifacts()).extracting(ChatArtifact::id).containsExactly("D1", "D2");
        assertThat(tabs[0].openIds()).containsExactly("D1");
        assertThat(tabs[0].deletedArtifacts()).isEmpty();
    }

    @Test
    void artifactsOpenAsTabsHighlightTheLastChangeAndAreRememberedPerChat() throws Exception {
        ChatStore chats = new ChatStore(() -> home.resolve("chats"));
        ChatNode node = new ChatNode("wiki", null, null);
        ChatRecord chat = ChatRecord.fresh(1).plus(ChatRecord.Message.user("Schreib", 1), 1);
        chats.save(node, chat);
        Path folder = chats.folder(node);
        ChatArtifactStore store = new ChatArtifactStore(chats);
        store.createDocument(folder, chat.id(), "Gartenfibel", "# Gartenfibel\n\n## Boden\n\nHumus.",
                ChatArtifact.Origin.AI);

        ArtifactTabs[] tabs = new ArtifactTabs[1];
        int[] revealed = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            tabs[0] = new ArtifactTabs(store, new JLabel("Seite"));
            tabs[0].reveal(() -> revealed[0]++);
            tabs[0].chat(folder, chat.id());
            tabs[0].open("d1");
        });
        {
            assertThat(revealed[0]).as("Öffnen holt die Export-Ansicht nach vorn").isEqualTo(1);
            assertThat(tabs[0].artifacts()).extracting(ChatArtifact::id).as("für das Menü „Weitere Aktionen“")
                    .containsExactly("D1");
            assertThat(tabs[0].openIds()).containsExactly("D1");
            assertThat(tabs[0].focus()).isEqualTo("D1");
            assertThat(tabs[0].selectedView().sectionOrder()).containsExactly("a1", "a2");
            // Markieren und zitieren (ADR 0045 §7): jedes Textfeld eines Abschnitts trägt Artefakt und Abschnitt.
            SwingUtilities.invokeAndWait(() -> {
                javax.swing.text.JTextComponent text = textIn(tabs[0].selectedView().block("a2"));
                assertThat(text).isNotNull();
                assertThat(com.aresstack.spaceharvester.app.ui.controls.TextContextMenu.quoteReference(text))
                        .isEqualTo("Dokument D1 \u201EGartenfibel\u201C, Abschnitt a2");
            });

            store.edit(folder, chat.id(), "D1", ChatArtifactStore.ANY, ChatArtifact.Origin.AI, "",
                    document -> document.replaceText("a2", "Lockerer Humus."));
            SwingUtilities.invokeAndWait(() -> { });
            assertThat(tabs[0].selectedView().highlighted()).containsExactly("a2");
            assertThat(tabs[0].selectedView().artifact().revision()).isEqualTo(2);

            SwingUtilities.invokeAndWait(() -> tabs[0].undo());
            assertThat(store.read(folder, chat.id(), "D1").document().section("a2").text())
                    .as("Rückgängig holt den alten Stand als neue Revision").isEqualTo("Humus.");
            assertThat(store.read(folder, chat.id(), "D1").revision()).isEqualTo(3);
            assertThat(tabs[0].selectedView().highlighted()).containsExactly("a2");

            SwingUtilities.invokeAndWait(() -> {
                tabs[0].selectedView().edit("a2");
                tabs[0].selectedView().editor().setText("## Boden\n\nSandiger Boden.");
            });
            store.edit(folder, chat.id(), "D1", ChatArtifactStore.ANY, ChatArtifact.Origin.AI, "",
                    document -> document.replaceText("a1", "Von der KI."));
            SwingUtilities.invokeAndWait(() -> tabs[0].selectedView().saveEdit());
            assertThat(tabs[0].selectedView().editor()).as("inzwischen geändert: der Editor bleibt offen").isNotNull();
            // Verwerfen und neu öffnen zeigt den neuen Stand; dann gilt das Übernehmen.
            SwingUtilities.invokeAndWait(() -> tabs[0].selectedView().closeEditor());
            SwingUtilities.invokeAndWait(() -> {
                tabs[0].selectedView().edit("a2");
                tabs[0].selectedView().editor().setText("## Boden\n\nSandiger Boden.");
                tabs[0].selectedView().saveEdit();
            });
            assertThat(tabs[0].selectedView().editor()).isNull();
            ChatArtifact edited = store.read(folder, chat.id(), "D1");
            assertThat(edited.document().section("a2").text()).isEqualTo("Sandiger Boden.");
            assertThat(edited.document().section("a1").text()).isEqualTo("Von der KI.");
            assertThat(edited.current().origin()).isEqualTo(ChatArtifact.Origin.USER);

            SwingUtilities.invokeAndWait(() -> tabs[0].showPage());
            assertThat(tabs[0].focus()).as("links gewählt: die Seite kommt zurück").isNull();
            assertThat(tabs[0].openIds()).as("Reiter bleiben stehen").containsExactly("D1");

            SwingUtilities.invokeAndWait(() -> {
                tabs[0].chat(folder, "anderer");
                tabs[0].chat(folder, chat.id());
            });
            assertThat(tabs[0].openIds()).as("der Chat merkt sich seine Reiter").containsExactly("D1");
        }
    }

    private static javax.swing.text.JTextComponent textIn(java.awt.Component component) {
        if (component instanceof javax.swing.text.JTextComponent text) {
            return text;
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                javax.swing.text.JTextComponent found = textIn(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
