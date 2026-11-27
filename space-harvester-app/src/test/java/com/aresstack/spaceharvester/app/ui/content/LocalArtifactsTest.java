package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.observe.Subscription;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** „Aus dieser Seite erzeugt“ unter „Export“ und im ▾-Menü (ADR 0047 §5a); erfundene Auswahl „wiki/DEMO“. */
class LocalArtifactsTest {

    private final List<String> done = new ArrayList<>();
    private List<LocalArtifacts.Item> stored = new ArrayList<>();
    private Runnable changed = () -> { };

    private final LocalArtifacts.Actions actions = new LocalArtifacts.Actions() {
        @Override
        public List<LocalArtifacts.Item> items(ExportPanel.Selection selection) {
            return List.copyOf(stored);
        }

        @Override
        public void renew(ExportPanel.Selection selection, LocalArtifacts.Item item) {
            done.add("neu:" + item.name());
        }

        @Override
        public void delete(ExportPanel.Selection selection, LocalArtifacts.Item item) {
            done.add("weg:" + item.name());
            stored.remove(item);
            changed.run();
        }

        @Override
        public Subscription subscribe(ExportPanel.Selection selection, List<LocalArtifacts.Item> items,
                                      Runnable listener) {
            changed = listener;
            return () -> changed = () -> { };
        }
    };

    @Test
    void theSectionShowsWhatExistsOpensDeletesAfterAskingAndTheMenuOffersTheSame() throws Exception {
        LocalArtifacts.Item summary = new LocalArtifacts.Item(LocalArtifacts.Kind.SUMMARY, LocalArtifacts.SUMMARY,
                true, 0L, true, null, null, null);
        LocalArtifacts.Item podcast = new LocalArtifacts.Item(LocalArtifacts.Kind.PODCAST, LocalArtifacts.PODCAST,
                false, null, false, null, "k", null);
        stored = new ArrayList<>(List.of(summary, podcast));
        List<String> opened = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        LocalArtifacts[] holder = new LocalArtifacts[1];
        SwingUtilities.invokeAndWait(() -> {
            LocalArtifacts section = new LocalArtifacts(actions);
            holder[0] = section;
            section.background = (work, then) -> {
                try {
                    then.accept(work.call());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            section.worker = Runnable::run;
            section.confirm = (title, question) -> asked.add(question);
            section.onOpen(() -> opened.add("Leseansicht"), () -> opened.add("Podcast-Ansicht"));

            // Ohne Auswahl und für „Wissensquellen“ nur der gedämpfte Satz
            section.selection(ExportPanel.Selection.ALL);
            assertThat(section.items()).isEmpty();
            assertThat(section.list().showsPlaceholder()).isTrue();
            assertThat(section.list().placeholder()).isEqualTo(LocalArtifacts.NONE);

            section.selection(ExportPanel.Selection.of("wiki", "DEMO", null, "Demo"));
            assertThat(section.items()).containsExactly(summary, podcast);

            section.open(summary);
            section.open(podcast);
            assertThat(opened).containsExactly("Leseansicht", "Podcast-Ansicht");

            // Solange der Podcast entsteht, kein Neuerzeugen und kein Löschen
            JPopupMenu busy = section.menu(podcast);
            assertThat(((JMenuItem) busy.getComponent(1)).isEnabled()).isFalse();
            assertThat(((JMenuItem) busy.getComponent(3)).isEnabled()).isFalse();

            // Das ▾-Menü: dieselben Einträge mit denselben Aktionen
            JPopupMenu more = new JPopupMenu();
            more.add(new JMenuItem(PageDetailPanel.NEW_EXPORT));
            section.addTo(more);
            List<String> labels = new ArrayList<>();
            for (java.awt.Component part : more.getComponents()) {
                if (part instanceof JMenuItem item) {
                    labels.add(item.getText());
                }
            }
            assertThat(labels).containsExactly(PageDetailPanel.NEW_EXPORT, LocalArtifacts.TITLE,
                    LocalArtifacts.SUMMARY, LocalArtifacts.PODCAST);
            JMenu entry = (JMenu) more.getComponent(more.getComponentCount() - 2);
            entry.getItem(1).doClick();
            assertThat(done).containsExactly("neu:" + LocalArtifacts.SUMMARY);

            section.delete(summary);
            assertThat(asked).containsExactly(LocalArtifacts.deleteQuestion(LocalArtifacts.SUMMARY));
            assertThat(done).containsExactly("neu:" + LocalArtifacts.SUMMARY, "weg:" + LocalArtifacts.SUMMARY);
        });
        // Die Ablage meldet das Löschen; danach steht nur noch der Podcast da
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> assertThat(holder[0].items()).containsExactly(podcast));
    }
}
