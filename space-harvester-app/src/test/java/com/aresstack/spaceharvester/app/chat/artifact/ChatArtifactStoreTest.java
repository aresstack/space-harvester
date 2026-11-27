package com.aresstack.spaceharvester.app.chat.artifact;

import com.aresstack.spaceharvester.app.chat.ChatNode;
import com.aresstack.spaceharvester.app.chat.ChatRecord;
import com.aresstack.spaceharvester.app.chat.ChatStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Artefakte liegen beim Chat, jede Änderung ist eine Revision, und sie gehen mit dem Chat (ADR 0045, Abschnitt 1). */
class ChatArtifactStoreTest {

    @TempDir
    Path home;

    private final ChatNode node = new ChatNode("wiki", "DEV", null);

    private ChatStore chats() {
        return new ChatStore(() -> home.resolve("chats"));
    }

    private ChatRecord chat(ChatStore chats) {
        ChatRecord chat = ChatRecord.fresh(1_000).plus(ChatRecord.Message.user("Schreib mir was", 1_000), 1_000);
        chats.save(node, chat);
        return chat;
    }

    @Test
    void documentsAreKeptWithAllRevisionsAndListedInCreationOrder() {
        ChatStore chats = chats();
        ChatRecord chat = chat(chats);
        ChatArtifactStore store = new ChatArtifactStore(chats);
        Path folder = chats.folder(node);
        List<ChatArtifactStore.Event> events = new ArrayList<>();
        store.addListener(events::add);

        ChatArtifact first = store.createDocument(folder, chat.id(), "Gartenfibel", "# Gartenfibel\n\nBohnen.",
                ChatArtifact.Origin.AI);
        ChatArtifact second = store.createDocument(folder, chat.id(), "Brief", "", ChatArtifact.Origin.USER);
        ChatArtifact graphic = store.createGraphic(folder, chat.id(), "Beet", "<svg/>", ChatArtifact.Origin.AI);

        assertThat(first.id()).isEqualTo("D1");
        assertThat(second.id()).isEqualTo("D2");
        assertThat(graphic.id()).isEqualTo("G1");
        assertThat(store.list(folder, chat.id())).extracting(ChatArtifact::id).containsExactly("D1", "D2", "G1");
        assertThat(chats.list(node)).as("die Chat-Liste liest keine Artefakte").hasSize(1);

        ChatArtifactStore.WriteResult edit = store.edit(folder, chat.id(), "d1", ChatArtifactStore.ANY,
                ChatArtifact.Origin.AI, "Text ersetzt", document -> document.replaceText("a1", "Erbsen."));
        assertThat(edit.success()).isTrue();
        assertThat(edit.revision()).isEqualTo(2);
        assertThat(edit.changed()).containsExactly("a1");

        ChatArtifact loaded = new ChatArtifactStore(chats()).read(folder, chat.id(), "D1");
        assertThat(loaded.content()).isEqualTo("# Gartenfibel\n\nErbsen.\n");
        assertThat(loaded.revisions()).hasSize(2);
        assertThat(loaded.revision(1).document().markdown()).contains("Bohnen.");
        assertThat(loaded.current().origin()).isEqualTo(ChatArtifact.Origin.AI);
        assertThat(events).extracting(event -> event.artifact().id()).containsExactly("D1", "D2", "G1", "D1");
    }

    @Test
    void theViewWritesOnlyOnTheExpectedRevisionAndToolsOnTheNewest() {
        ChatStore chats = chats();
        ChatRecord chat = chat(chats);
        ChatArtifactStore store = new ChatArtifactStore(chats);
        Path folder = chats.folder(node);
        store.createDocument(folder, chat.id(), "Brief", "## Anrede\n\nHallo.", ChatArtifact.Origin.AI);

        assertThat(store.edit(folder, chat.id(), "D1", 1, ChatArtifact.Origin.USER, "",
                document -> document.replaceText("a1", "Guten Tag.")).success()).isTrue();
        ChatArtifactStore.WriteResult stale = store.edit(folder, chat.id(), "D1", 1, ChatArtifact.Origin.USER, "",
                document -> document.replaceText("a1", "Moin."));
        assertThat(stale.success()).isFalse();
        assertThat(stale.reason()).contains("inzwischen geändert");
        assertThat(stale.artifact().document().section("a1").text()).isEqualTo("Guten Tag.");

        ChatArtifactStore.WriteResult wrong = store.edit(folder, chat.id(), "D1", ChatArtifactStore.ANY,
                ChatArtifact.Origin.AI, "", document -> document.replaceText("a7", "x"));
        assertThat(wrong.success()).isFalse();
        assertThat(wrong.reason()).contains("Vorhandene Kennungen: a1");
        assertThat(store.read(folder, chat.id(), "D1").revision()).as("Fehler schreiben keine Revision").isEqualTo(2);

        assertThat(store.edit(folder, chat.id(), "D9", ChatArtifactStore.ANY, ChatArtifact.Origin.AI, "",
                document -> document.delete("a1")).reason()).contains("dokumente_auflisten");
    }

    @Test
    void undoGoesBackStepByStepAsNewRevisions() {
        ChatStore chats = chats();
        ChatRecord chat = chat(chats);
        ChatArtifactStore store = new ChatArtifactStore(chats);
        Path folder = chats.folder(node);
        store.createDocument(folder, chat.id(), "Liste", "## Punkt\n\neins", ChatArtifact.Origin.AI);
        store.edit(folder, chat.id(), "D1", ChatArtifactStore.ANY, ChatArtifact.Origin.AI, "",
                document -> document.replaceText("a1", "zwei"));
        store.edit(folder, chat.id(), "D1", ChatArtifactStore.ANY, ChatArtifact.Origin.AI, "",
                document -> document.replaceText("a1", "drei"));

        ChatArtifactStore.WriteResult once = store.undo(folder, chat.id(), "D1", ChatArtifactStore.ANY,
                ChatArtifact.Origin.USER);
        assertThat(once.artifact().document().section("a1").text()).isEqualTo("zwei");
        assertThat(once.changed()).containsExactly("a1");
        ChatArtifactStore.WriteResult twice = store.undo(folder, chat.id(), "D1", ChatArtifactStore.ANY,
                ChatArtifact.Origin.USER);
        assertThat(twice.artifact().document().section("a1").text()).isEqualTo("eins");
        assertThat(twice.artifact().undoable()).isFalse();
        assertThat(store.undo(folder, chat.id(), "D1", ChatArtifactStore.ANY, ChatArtifact.Origin.USER).success())
                .isFalse();
        assertThat(twice.artifact().revisions()).as("nichts überschrieben").hasSize(5);
    }

    @Test
    void anUndoneInsertionNeverGivesItsIdAgain() {
        ChatStore chats = chats();
        ChatRecord chat = chat(chats);
        ChatArtifactStore store = new ChatArtifactStore(chats);
        Path folder = chats.folder(node);
        store.createDocument(folder, chat.id(), "Liste", "## Punkt\n\neins", ChatArtifact.Origin.AI);
        store.edit(folder, chat.id(), "D1", ChatArtifactStore.ANY, ChatArtifact.Origin.AI, "",
                document -> document.insertAfter("a1", 2, "Zweiter", "zwei"));
        store.undo(folder, chat.id(), "D1", ChatArtifactStore.ANY, ChatArtifact.Origin.USER);

        ChatArtifactStore.WriteResult again = store.edit(folder, chat.id(), "D1", ChatArtifactStore.ANY,
                ChatArtifact.Origin.AI, "", document -> document.insertAfter("a1", 2, "Anderer", "drei"));

        assertThat(again.changed()).containsExactly("a3");
    }

    @Test
    void artifactsGoWithTheChatIntoTheTrashBackAndAway() {
        ChatStore chats = chats();
        ChatRecord chat = chat(chats);
        ChatArtifactStore store = new ChatArtifactStore(chats);
        Path folder = chats.folder(node);
        store.createDocument(folder, chat.id(), "Notiz", "Text", ChatArtifact.Origin.AI);

        chats.trash(folder, chat.id());
        assertThat(store.list(folder, chat.id())).isEmpty();
        chats.restore(folder, chat.id());
        assertThat(store.list(folder, chat.id())).hasSize(1);

        chats.delete(folder, chat.id());
        assertThat(store.list(folder, chat.id())).isEmpty();
        assertThat(Files.exists(chats.artifacts(folder, chat.id()))).isFalse();
    }

    @Test
    void deletingAllChatsRemovesAllArtifacts() {
        ChatStore chats = chats();
        ChatRecord chat = chat(chats);
        ChatArtifactStore store = new ChatArtifactStore(chats);
        store.createDocument(chats.folder(node), chat.id(), "Notiz", "Text", ChatArtifact.Origin.AI);

        chats.deleteAll();

        assertThat(store.list(chats.folder(node), chat.id())).isEmpty();
    }

    @Test
    void withoutDataFolderNothingIsCreated() {
        ChatArtifactStore store = new ChatArtifactStore(new ChatStore(() -> null));

        assertThat(store.createDocument(Path.of("wiki"), "abc", "x", "y", ChatArtifact.Origin.AI)).isNull();
        assertThat(store.list(Path.of("wiki"), "abc")).isEmpty();
    }
}
