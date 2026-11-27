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
    void inAProjectTheArtifactsBelongToTheProjectAndAChatBringsItsOwnAlong() throws Exception {
        // ADR 0043, F3: im Projekt besitzt das Projekt die Artefakte, alle Chats des Projekts arbeiten darauf.
        ChatStore chats = chats();
        Path folder = chats.folder(node);
        Path project = home.resolve("projekte").resolve("p1").resolve("artefakte");
        ChatArtifactStore store = new ChatArtifactStore(chats);
        store.projects(id -> "p1".equals(id) ? project : null);
        List<ChatArtifactStore.Event> events = new ArrayList<>();
        store.addListener(events::add);
        ChatRecord first = ChatRecord.fresh(1_000, "p1");
        ChatRecord second = ChatRecord.fresh(2_000, "p1");
        store.chatProject(first.id(), "p1");
        store.chatProject(second.id(), "p1");
        store.createDocument(folder, first.id(), "Plan", "# Plan\n\nErst säen.", ChatArtifact.Origin.AI);
        assertThat(store.list(folder, second.id())).extracting(ChatArtifact::id).as("der zweite Chat sieht es")
                .containsExactly("D1");
        assertThat(events.get(0).owner()).isEqualTo(project).isEqualTo(store.owner(folder, second.id()));
        assertThat(Files.isDirectory(chats.artifacts(folder, first.id()))).as("nichts beim Chat").isFalse();
        store.createGraphic(folder, second.id(), "Ablauf", "<svg/>", ChatArtifact.Origin.AI);

        // Ein Chat aus dem Standardprojekt kommt mit Dokument und eingebundener Grafik ins Projekt.
        ChatRecord loose = chat(chats);
        ChatArtifact graphic = store.createGraphic(folder, loose.id(), "Beet", "<svg/>", ChatArtifact.Origin.AI);
        store.createDocument(folder, loose.id(), "Gartenfibel", "# Gartenfibel\n\n"
                + GraphicReferences.reference(graphic), ChatArtifact.Origin.AI);
        store.rememberTabs(folder, loose.id(), List.of("D1", "G1"));
        java.util.Map<String, String> renamed = store.moveToProject(folder, loose.id(), "p1");
        assertThat(renamed).containsOnly(java.util.Map.entry("G1", "G2"), java.util.Map.entry("D1", "D2"));
        // Verweise in den Antworten des Chats folgen (ChatPanel schreibt den Verlauf damit um).
        assertThat(ChatArtifactStore.relink("Siehe [Gartenfibel](artefakt:D1) und artefakt:D12.", renamed))
                .isEqualTo("Siehe [Gartenfibel](artefakt:D2) und artefakt:D12.");
        assertThat(store.moveToProject(folder, loose.id(), "p1")).as("ein zweites Mal zieht nichts um").isEmpty();

        // Im selben Augenblick angelegte Artefakte stehen nach Kennung; die Reihenfolge der beiden ist hier gleich.
        assertThat(store.list(folder, second.id())).extracting(ChatArtifact::id)
                .containsExactlyInAnyOrder("D1", "G1", "G2", "D2");
        assertThat(GraphicReferences.ids(store.read(folder, second.id(), "D2").content()))
                .as("der Verweis folgt der neuen Kennung").containsExactly("G2");
        assertThat(store.read(folder, second.id(), "D2").title()).isEqualTo("Gartenfibel");
        assertThat(store.openTabs(folder, loose.id())).as("die Reiter folgen").containsExactly("D2", "G2");
        assertThat(store.list(folder, loose.id())).hasSize(4);
        try (var left = Files.list(chats.artifacts(folder, loose.id()))) {
            assertThat(left.filter(file -> file.toString().endsWith(ChatArtifactStore.SUFFIX)))
                    .as("beim Chat bleibt keine Kopie").isEmpty();
        }
    }

    @Test
    void theStandardProjectOwnsItsArtifactsAndOldOnesMoveOverOnce() throws Exception {
        // ADR 0047: das Projekt besitzt immer, auch „AresStack“; ein neuer Chat sieht die Artefakte älterer Chats.
        ChatStore chats = chats();
        Path folder = chats.folder(node);
        ChatRecord old = chat(chats);
        ChatRecord other = ChatRecord.fresh(2_000).plus(ChatRecord.Message.user("Noch was", 2_000), 2_000);
        chats.save(node, other);
        // Ablage wie vor ADR 0047: jeder Chat des Standardprojekts besitzt seine Artefakte selbst.
        ChatArtifactStore before = new ChatArtifactStore(chats);
        ChatArtifact graphic = before.createGraphic(folder, old.id(), "Beet", "<svg/>", ChatArtifact.Origin.AI);
        before.createDocument(folder, old.id(), "Gartenfibel", "# Gartenfibel\n\n"
                + GraphicReferences.reference(graphic), ChatArtifact.Origin.AI);
        before.rememberTabs(folder, old.id(), List.of("D1"));
        before.createDocument(folder, other.id(), "Brief", "# Brief\n\nHallo.", ChatArtifact.Origin.AI);
        chats.save(folder, old.plus(ChatRecord.Message.assistant("Fertig: [Gartenfibel](artefakt:D1)", false, 3_000),
                3_000));

        Path standard = home.resolve("projekte").resolve("standard").resolve("artefakte");
        ChatArtifactStore store = new ChatArtifactStore(chats);
        store.projects(id -> "standard".equals(id) ? standard : null);
        assertThat(store.owner(folder, old.id())).isEqualTo(standard).isEqualTo(store.owner(folder, other.id()));

        assertThat(store.adoptAll()).isEqualTo(3);
        assertThat(store.adoptAll()).as("einmalig: danach liegt nichts mehr beim Chat").isZero();
        ChatRecord fresh = ChatRecord.fresh(4_000);
        assertThat(store.list(folder, fresh.id())).as("ein neuer Chat sieht alles").extracting(ChatArtifact::title)
                .containsExactlyInAnyOrder("Beet", "Gartenfibel", "Brief");
        ChatArtifact fibel = store.list(folder, fresh.id()).stream()
                .filter(artifact -> artifact.title().equals("Gartenfibel")).findFirst().orElseThrow();
        ChatArtifact beet = store.list(folder, fresh.id()).stream()
                .filter(artifact -> artifact.title().equals("Beet")).findFirst().orElseThrow();
        assertThat(GraphicReferences.ids(fibel.content())).as("der Verweis bleibt gültig").containsExactly(beet.id());
        assertThat(store.openTabs(folder, old.id())).containsExactly(fibel.id());
        assertThat(chats.load(folder, old.id()).messages().get(1).text())
                .as("der Verlauf folgt den neuen Kennungen").contains("artefakt:" + fibel.id());
        assertThat(ChatArtifactStore.listIn(chats.artifacts(folder, old.id()))).isEmpty();
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
