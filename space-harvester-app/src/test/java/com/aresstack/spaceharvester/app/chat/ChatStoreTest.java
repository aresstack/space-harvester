package com.aresstack.spaceharvester.app.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Chats liegen je Bereich (Quelle, Space, Seite) im Datenordner, als JSON je Chat; leere Chats hinterlassen nichts. */
class ChatStoreTest {

    @TempDir
    Path home;

    @Test
    void chatsAreKeptPerNodeAndListedNewestFirst() {
        ChatStore store = new ChatStore(() -> home.resolve("chats"));
        ChatNode space = new ChatNode("wiki", "DEV", null);
        ChatNode page = new ChatNode("wiki", "DEV", "42");
        ChatRecord older = ChatRecord.fresh(1_000).plus(ChatRecord.Message.user("Hallo", 1_000), 1_000)
                .plus(ChatRecord.Message.assistant("Hi", false, 1_500), 1_500);
        ChatRecord newer = ChatRecord.fresh(2_000).plus(ChatRecord.Message.user("Noch was", 2_000), 2_000);
        store.save(space, older);
        store.save(space, newer);
        store.save(page, ChatRecord.fresh(3_000).plus(ChatRecord.Message.user("Zur Seite", 3_000), 3_000));

        assertThat(store.list(space)).extracting(ChatRecord::id).containsExactly(newer.id(), older.id());
        assertThat(store.list(page)).hasSize(1);
        assertThat(store.list(new ChatNode("wiki", null, null))).isEmpty();
        assertThat(home.resolve("chats/wiki/DEV/_space").resolve(older.id() + ".json")).exists();
        assertThat(home.resolve("chats/wiki/DEV/42").toFile().list()).hasSize(1);

        ChatRecord loaded = store.load(space, older.id());
        assertThat(loaded.messages()).hasSize(2);
        assertThat(loaded.messages().get(1).assistant()).isTrue();
        assertThat(loaded.title()).as("Titel ist anfangs die Kennung").isEqualTo(older.id());
        assertThat(loaded.renamed()).isFalse();
    }

    @Test
    void renamingKeepsTheIdAndDeletingRemovesTheFile() {
        ChatStore store = new ChatStore(() -> home.resolve("chats"));
        ChatNode node = new ChatNode("wiki", null, null);
        ChatRecord chat = ChatRecord.fresh(1_000).plus(ChatRecord.Message.user("Frage", 1_000), 1_000);
        store.save(node, chat.withTitle("Mein Chat", 2_000));
        ChatRecord renamed = store.load(node, chat.id());
        assertThat(renamed.title()).isEqualTo("Mein Chat");
        assertThat(renamed.renamed()).isTrue();
        assertThat(renamed.modifiedAt()).isEqualTo(2_000);

        store.delete(node, chat.id());
        assertThat(store.load(node, chat.id())).isNull();
        assertThat(store.list(node)).isEmpty();
    }

    @Test
    void theNumberedSourcesOfAnAnswerAreKeptAndOlderChatsLoadWithout() throws Exception {
        ChatStore store = new ChatStore(() -> home.resolve("chats"));
        ChatNode node = new ChatNode("wiki", "DEV", "42");
        List<ChatRecord.Source> sources = List.of(
                new ChatRecord.Source("Nachtlauf", "wiki", "42", "https://wiki.example/42"),
                new ChatRecord.Source("Überblick", "wiki", null, null));
        ChatRecord chat = ChatRecord.fresh(1).plus(ChatRecord.Message.user("Frage", 1, "Seite Nachtlauf", "wiki/DEV/42"), 1)
                .plus(ChatRecord.Message.assistant("Antwort [1]", false, 2, sources), 2);
        store.save(node, chat);

        ChatRecord loaded = store.load(node, chat.id());
        assertThat(loaded.messages().get(1).sources()).isEqualTo(sources);
        assertThat(loaded.messages().get(0).sources()).isEmpty();
        String json = Files.readString(node.directory(home.resolve("chats")).resolve(chat.id() + ".json"));
        assertThat(json).as("Fragen schreiben keine leere Quellenliste").containsOnlyOnce("\"sources\"");

        Files.writeString(node.directory(home.resolve("chats")).resolve("alt.json"), """
                {"id":"alt","title":"alt","createdAt":1,"modifiedAt":2,"messages":[
                {"role":"user","text":"Frage","cancelled":false,"at":1},
                {"role":"assistant","text":"Antwort","cancelled":false,"at":2}]}
                """);
        assertThat(store.load(node, "alt").messages()).allSatisfy(message -> assertThat(message.sources()).isEmpty());
    }

    @Test
    void anEmptyChatIsNeverWrittenAndBrokenFilesAreSkipped() throws Exception {
        ChatStore store = new ChatStore(() -> home.resolve("chats"));
        ChatNode node = new ChatNode("wiki", "DEV", null);
        store.save(node, ChatRecord.fresh(1));
        assertThat(home.resolve("chats")).doesNotExist();
        ChatRecord chat = ChatRecord.fresh(1).plus(ChatRecord.Message.user("x", 1), 1);
        store.save(node, chat);
        Files.writeString(node.directory(home.resolve("chats")).resolve("kaputt.json"), "{nicht json");
        assertThat(store.list(node)).extracting(ChatRecord::id).containsExactly(chat.id());
        // Kein Datenordner (App noch ohne Einstellungen): nichts zu lesen, nichts zu schreiben.
        ChatStore without = new ChatStore(() -> null);
        assertThat(without.list(node)).isEqualTo(List.of());
        without.save(node, chat);
    }

    @Test
    void nodeSegmentsStayReadableAndSafe() {
        assertThat(ChatNode.segment("DEV")).isEqualTo("DEV");
        assertThat(ChatNode.segment("confluence.example")).isEqualTo("confluence.example");
        assertThat(ChatNode.segment("a/b c")).startsWith("a_b_c-").hasSize("a_b_c-".length() + 8);
        assertThat(ChatNode.segment("..")).startsWith("..-");
        assertThat(ChatNode.segment("a/b")).isNotEqualTo(ChatNode.segment("a_b"));
        assertThat(new ChatNode("q", null, null).directory(Path.of("r"))).isEqualTo(Path.of("r/q/_quelle/_space"));
        assertThat(ChatNode.ALL.directory(Path.of("r"))).isEqualTo(Path.of("r/_alle"));
        // Eine Sammlung liegt bei ihrer Ordner-ID, nie neben einer Quelle gleichen Namens.
        assertThat(ChatNode.collection("f-1").directory(Path.of("r"))).isEqualTo(Path.of("r/_sammlung/f-1"));
        assertThat(new ChatNode("_sammlung", null, null).directory(Path.of("r")).startsWith(Path.of("r/_sammlung")))
                .isFalse();
    }

    @Test
    void theProjectOfAChatIsStoredGoesThroughTheTrashAndOlderChatsHaveNone() throws Exception {
        ChatStore store = new ChatStore(() -> home.resolve("chats"));
        ChatNode node = new ChatNode("wiki", "DEV", null);
        ChatRecord chat = ChatRecord.fresh(1_000, "p1", "g1").plus(ChatRecord.Message.user("Frage", 1_000), 1_000);
        store.save(node, chat);
        assertThat(store.load(node, chat.id()).projectId()).isEqualTo("p1");
        assertThat(store.load(node, chat.id()).groupId()).isEqualTo("g1");

        Path folder = store.folder(node);
        store.trash(folder, chat.id());
        ChatRecord restored = store.restore(folder, chat.id());
        assertThat(restored.projectId()).isEqualTo("p1");
        assertThat(restored.groupId()).isEqualTo("g1");
        assertThat(restored.inGroup(null).projectId()).isEqualTo("p1");
        assertThat(restored.inProject("p2").groupId()).as("Verschieben nimmt aus der Gruppe").isNull();

        ChatRecord removed = store.load(node, chat.id()).inProject(null);
        assertThat(removed.modifiedAt()).as("Zuordnen ändert die Reihenfolge nicht").isEqualTo(1_000);
        store.save(node, removed);
        assertThat(store.load(node, chat.id()).projectId()).isNull();
        assertThat(Files.readString(home.resolve("chats").resolve(folder).resolve(chat.id() + ".json")))
                .doesNotContain("projectId").doesNotContain("groupId");

        Path old = home.resolve("chats").resolve(folder).resolve("alt.json");
        Files.writeString(old, "{\"id\":\"alt\",\"title\":\"alt\",\"createdAt\":1,\"modifiedAt\":1,"
                + "\"messages\":[{\"role\":\"user\",\"text\":\"x\",\"cancelled\":false,\"at\":1}]}");
        assertThat(store.load(node, "alt").projectId()).isNull();
        assertThat(store.load(node, "alt").groupId()).isNull();
    }
}
