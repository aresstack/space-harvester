package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.chat.ChatNode;
import com.aresstack.spaceharvester.app.chat.ChatRecord;
import com.aresstack.spaceharvester.app.chat.ChatStore;
import com.aresstack.spaceharvester.app.project.ChatGroup;
import com.aresstack.spaceharvester.app.project.Project;
import com.aresstack.spaceharvester.app.project.ProjectStore;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Chat-Liste zeigt nur das aktive Projekt, oben seine farbigen Gruppen zum Aufklappen (ADR 0043, F2, F6). */
class ChatPanelProjectsTest {

    @TempDir
    Path home;

    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    private static List<String> menuTexts(ChatPanel panel) {
        return panel.chatMenuItems().stream()
                .map(item -> item instanceof JMenuItem menu ? menu.getText() : "—").toList();
    }

    @Test
    void onlyTheActiveProjectIsListedWithItsGroupsAndNewChatsBelongToIt() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("seite.md"), "# Seite\n\nText.\n");
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: handbuch
                      type: files
                      title: Handbuch
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), files));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        String space = context.runtime().workspace().spaces("handbuch").get(0).key();
        ProjectStore projects = context.projects();
        Project umzug = projects.create("Umzug");
        ChatGroup entwurf = projects.createGroup(umzug.id(), "Entwurf", null);
        ChatStore store = new ChatStore(context::chatsDirectory);
        ChatNode spaceNode = new ChatNode("handbuch", space, null);
        ChatRecord grouped = ChatRecord.fresh(3_000, umzug.id(), entwurf.id())
                .plus(ChatRecord.Message.user("Kartons zählen", 3_000), 3_000);
        ChatRecord loose = ChatRecord.fresh(2_000, umzug.id())
                .plus(ChatRecord.Message.user("Termine", 2_000), 2_000);
        ChatRecord general = ChatRecord.fresh(1_000).plus(ChatRecord.Message.user("Allgemeines", 1_000), 1_000);
        store.save(spaceNode, grouped);
        store.save(spaceNode, loose);
        store.save(spaceNode, general);
        projects.activate(umzug.id());

        ChatPanel[] panel = new ChatPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new ChatPanel(context);
            panel[0].selection(new Selection.Node("handbuch", space, null, "Handbuch"));
            assertThat(panel[0].chat().id()).as("zuletzt geänderter Chat des aktiven Projekts").isEqualTo(grouped.id());
            assertThat(menuTexts(panel[0])).containsExactly("Entwurf  (1)", "—", ChatPanel.CHATS_HERE,
                    "    Termine");
            ChatPanel.GroupRow row = (ChatPanel.GroupRow) panel[0].chatMenuItems().get(0);
            row.toggle();
            assertThat(menuTexts(panel[0])).containsExactly("Entwurf  (1)", ChatPanel.NEW_CHAT_IN_GROUP,
                    "✓ Kartons zählen", "—", ChatPanel.CHATS_HERE, "    Termine");

            ((JMenuItem) panel[0].chatMenuItems().get(1)).doClick();
            assertThat(panel[0].chat().isEmpty()).isTrue();
            assertThat(panel[0].chat().projectId()).isEqualTo(umzug.id());
            assertThat(panel[0].chat().groupId()).as("neuer Chat in dieser Gruppe").isEqualTo(entwurf.id());
        });

        projects.activate(Project.DEFAULT_ID);
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> {
            assertThat(panel[0].chat().id()).as("Wechsel unten in der Ecke: der Chat folgt").isEqualTo(general.id());
            assertThat(menuTexts(panel[0])).containsExactly(ChatPanel.CHATS_HERE, "✓ Allgemeines");
        });
    }
}
