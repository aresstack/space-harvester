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
        SwingUtilities.invokeAndWait(() -> {
            tabs[0] = new ArtifactTabs(store, new JLabel("Seite"));
            tabs[0].chat(folder, chat.id());
            tabs[0].open("d1");
        });
        {
            assertThat(tabs[0].openIds()).containsExactly("D1");
            assertThat(tabs[0].focus()).isEqualTo("D1");
            assertThat(tabs[0].selectedView().sectionOrder()).containsExactly("a1", "a2");

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
}
