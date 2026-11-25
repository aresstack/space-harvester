package com.aresstack.spaceharvester.app.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ChatExportTest {
    @TempDir Path directory;

    @Test
    void allScopesAndFormatsUseExistingWritersAndPreserveSourceIdentity() throws Exception {
        ChatRecord.Source one = new ChatRecord.Source("Handbuch", "docs", "1", "https://example.org/1");
        ChatRecord.Source two = new ChatRecord.Source("Handbuch", "docs", "2", "https://example.org/2");
        ChatRecord.Message first = ChatRecord.Message.assistant("**Antwort eins** [1]", false, 2, List.of(one));
        ChatRecord.Message second = ChatRecord.Message.assistant("Antwort zwei [1]", true, 3, List.of(two, one));
        ChatRecord chat = ChatRecord.fresh(1).plus(ChatRecord.Message.user("Frage: Datenhaus?", 1), 1)
                .plus(first, 2).plus(second, 3);
        assertThat(ChatExport.sources(chat.messages())).containsExactly(one, two);
        assertThat(ChatExport.document(chat, first, ChatExport.Scope.ANSWER).children()).hasSize(1);
        assertThat(ChatExport.document(chat, first, ChatExport.Scope.ANSWER).children().get(0).text())
                .contains("Antwort eins", "https://example.org/1").doesNotContain("https://example.org/2");
        assertThat(ChatExport.document(chat, null, ChatExport.Scope.SOURCES).text())
                .contains("https://example.org/1", "https://example.org/2").doesNotContain("Antwort eins");
        for (ChatExport.Scope scope : ChatExport.Scope.values()) {
            for (ChatExport.Format format : ChatExport.Format.values()) {
                ChatRecord.Message answer = scope == ChatExport.Scope.ANSWER ? first : null;
                Path target = directory.resolve(ChatExport.filename(chat, answer == null ? 0 : 1, scope, format));
                ChatExport.write(ChatExport.document(chat, answer, scope), format, target);
                assertThat(Files.size(target)).isPositive();
            }
        }
        assertThat(ChatExport.filename(chat, 1, ChatExport.Scope.ANSWER, ChatExport.Format.PDF))
                .contains("Datenhaus", "Antwort-1").doesNotContain("?", ":", chat.id());
    }

    @Test
    void unnamedChatsUseShortDistinctQuestionTitlesWithoutChangingIdentity() {
        ChatRecord empty = ChatRecord.fresh(1);
        ChatRecord first = empty.plus(ChatRecord.Message.user("Erste Frage", 1), 1);
        ChatRecord second = ChatRecord.fresh(2).plus(ChatRecord.Message.user("Zweite\nFrage", 2), 2);
        assertThat(ChatExport.displayTitle(empty)).isEqualTo("Neuer Chat");
        assertThat(ChatExport.displayTitle(first)).isEqualTo("Erste Frage");
        assertThat(ChatExport.displayTitle(second)).isEqualTo("Zweite Frage");
        assertThat(ChatExport.displayTitle(first.withTitle("Fachlicher Titel", 3))).isEqualTo("Fachlicher Titel");
        assertThat(first.title()).isEqualTo(first.id());
        ChatRecord longQuestion = empty.plus(ChatRecord.Message.user("Ein langer Titel ".repeat(8), 1), 1);
        assertThat(ChatExport.displayTitle(longQuestion)).hasSizeLessThanOrEqualTo(50).endsWith("…");
    }

    @Test
    void titleRequestUsesNoToolsAndDoesNotModifyTheChat() {
        ChatRecord chat = ChatRecord.fresh(1).plus(ChatRecord.Message.user("Datenhaus", 1), 1);
        assertThat(ChatTitle.request(chat, "model", "low").tools()).isEmpty();
        assertThat(ChatTitle.request(chat, "model", "low").messages().get(1).text()).contains("Datenhaus");
        assertThat(ChatTitle.clean("  \"Datenhaus\"\nignored")).isEqualTo("Datenhaus");
        assertThat(chat.renamed()).isFalse();
        assertThat(chat.withTitle("Datenhaus", 2).id()).isEqualTo(chat.id());
    }
}
