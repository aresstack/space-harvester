package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.sync.Exclusions.Exclusion;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Übersicht „Ausgeschlossen“ (Angelo 26.11.2026): alle Ausschlüsse mit ✕ zum Wiedereinblenden, darunter die oberste
 * Ebene vom Server mit Häkchen zum Ausschließen. Jede Änderung meldet sich, damit der Baum neu lädt.
 */
class ExclusionsDialogTest {

    /** Erfundene SharePoint-Bibliotheken, wie sie auf oberster Ebene erscheinen. */
    private static final List<ExclusionsDialog.Top> TOP = List.of(
            new ExclusionsDialog.Top("sp", "lib-docs", "Freigegebene Dokumente"),
            new ExclusionsDialog.Top("sp", "lib-style", "Style Library"),
            new ExclusionsDialog.Top("sp", "lib-assets", "SiteAssets"));

    private static final class Fake implements ExclusionsDialog.Access {

        final List<Exclusion> excluded = new ArrayList<>();

        @Override
        public synchronized List<Exclusion> exclusions(String sourceId) {
            return List.copyOf(excluded);
        }

        @Override
        public List<ExclusionsDialog.Top> topLevel(String sourceId) {
            return TOP;
        }

        @Override
        public synchronized void exclude(String sourceId, String spaceKey, String pageId) {
            String title = TOP.stream().filter(top -> Objects.equals(top.pageId(), pageId)).map(
                    ExclusionsDialog.Top::title).findFirst().orElse("");
            excluded.add(new Exclusion(sourceId, spaceKey, pageId, title));
        }

        @Override
        public synchronized void include(String sourceId, String spaceKey, String pageId) {
            excluded.removeIf(exclusion -> exclusion.spaceKey().equals(spaceKey)
                    && Objects.equals(exclusion.pageId(), pageId));
        }
    }

    @Test
    void excludingAtTheTopLevelAndIncludingAgainOnlyChangesTheScope() throws Exception {
        Fake fake = new Fake();
        fake.exclude("sp", "sp", "tief-unten");
        AtomicInteger changes = new AtomicInteger();
        ExclusionsDialog[] dialog = new ExclusionsDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            dialog[0] = new ExclusionsDialog("sp", fake, changes::incrementAndGet);
            dialog[0].reload();
        });
        Background.awaitIdle(Duration.ofSeconds(30));

        SwingUtilities.invokeAndWait(() -> {
            // Ein Ordner aus dem Rechtsklick hat keinen bekannten Titel und steht mit seiner ID da.
            assertThat(dialog[0].listed()).containsExactly("tief-unten · sp");
            assertThat(dialog[0].topTable.getRowCount()).isEqualTo(3);
            assertThat(dialog[0].topTable.getValueAt(1, ExclusionsDialog.TOP_EXCLUDED)).isEqualTo(false);
            dialog[0].topTable.setValueAt(true, 1, ExclusionsDialog.TOP_EXCLUDED);
        });
        Background.awaitIdle(Duration.ofSeconds(30));

        SwingUtilities.invokeAndWait(() -> {
            assertThat(dialog[0].listed()).containsExactly("tief-unten · sp", "Style Library · sp");
            assertThat(dialog[0].topTable.getValueAt(1, ExclusionsDialog.TOP_EXCLUDED)).isEqualTo(true);
            dialog[0].includeRow(0);
        });
        Background.awaitIdle(Duration.ofSeconds(30));

        SwingUtilities.invokeAndWait(() -> {
            assertThat(dialog[0].listed()).containsExactly("Style Library · sp");
            dialog[0].topTable.setValueAt(false, 1, ExclusionsDialog.TOP_EXCLUDED);
        });
        Background.awaitIdle(Duration.ofSeconds(30));

        SwingUtilities.invokeAndWait(() -> assertThat(dialog[0].listed()).isEmpty());
        assertThat(fake.excluded).isEmpty();
        assertThat(changes.get()).isEqualTo(3);
    }
}
