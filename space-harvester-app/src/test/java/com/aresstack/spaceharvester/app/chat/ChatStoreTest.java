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
    }
}
