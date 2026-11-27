package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.chat.ChatRecord;
import com.aresstack.spaceharvester.app.chat.ChatStore;
import com.aresstack.spaceharvester.app.group.GroupColor;
import com.aresstack.spaceharvester.app.project.ChatGroup;
import com.aresstack.spaceharvester.app.project.Project;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Chat-Liste zeigt nur das aktive Projekt, gruppiert, jeden Chat genau einmal (ADR 0043, F2). */
class ChatListingTest {

    private static ChatStore.Entry entry(String id, String project, String group) {
        return new ChatStore.Entry(Path.of("wiki"), new ChatRecord(id, id, 1, 1,
                List.of(ChatRecord.Message.user("x", 1)), project, group));
    }

    @Test
    void onlyTheActiveProjectIsListedGroupedOnce() {
        ChatGroup entwurf = new ChatGroup("g1", "Entwurf", GroupColor.BLAU);
        ChatGroup leer = new ChatGroup("g2", "Leer", GroupColor.GRUEN);
        List<ChatStore.Entry> all = List.of(entry("a", "p1", "g1"), entry("b", "p1", null),
                entry("c", "p1", "weg"), entry("d", "p2", "g1"), entry("e", null, null), entry("f", "gelöscht", null));

        ChatListing p1 = ChatListing.of(all, "p1", Set.of("p1", "p2"), List.of(entwurf, leer));
        assertThat(p1.grouped().keySet()).containsExactly(entwurf, leer);
        assertThat(p1.grouped().get(entwurf)).extracting(e -> e.record().id()).containsExactly("a");
        assertThat(p1.grouped().get(leer)).isEmpty();
        assertThat(p1.ungrouped()).extracting(e -> e.record().id()).as("unbekannte Gruppe = ohne Gruppe")
                .containsExactly("b", "c");

        ChatListing standard = ChatListing.of(all, Project.DEFAULT_ID, Set.of("p1", "p2"), List.of());
        assertThat(standard.ungrouped()).extracting(e -> e.record().id())
                .as("ohne oder mit gelöschtem Projekt gehört der Chat zu AresStack").containsExactly("e", "f");
    }

    @Test
    void newChatsInTheDefaultProjectCarryNoId() {
        assertThat(ChatListing.idFor(Project.standard())).isNull();
        assertThat(ChatListing.idFor(new Project("p1", "Umzug", null))).isEqualTo("p1");
    }
}
