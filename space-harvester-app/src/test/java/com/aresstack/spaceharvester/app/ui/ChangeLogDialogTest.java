package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.changelog.ChangeEntries;
import com.aresstack.spaceharvester.application.changelog.ChangeEntry;
import com.aresstack.spaceharvester.application.changelog.ChangeFilter;
import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.observe.Subscription;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Der Dialog „Änderungsprotokoll“ unter Einstellungen › Cache (ADR 0030): Tabelle, Suche, Filter, Seite öffnen. */
class ChangeLogDialogTest {

    private static final Instant AT = Instant.parse("2026-11-16T19:00:00Z");
    private final List<String> opened = new CopyOnWriteArrayList<>();

    private static ChangeEntry entry(String pageId, ChangeKind kind, ChangeTrigger trigger, String before,
                                     String after) {
        return new ChangeEntry("wiki", "DEV", pageId, AT, kind, trigger, "1", "2", before, after, null, null, null,
                null, null, false);
    }

    /** Ein Protokoll im Speicher; merkt sich die Filter, die der Dialog schickt. */
    private static final class Log implements ChangeLogDialog.Access {
        final List<ChangeEntry> rows = new ArrayList<>();
        final List<ChangeFilter> asked = new CopyOnWriteArrayList<>();
        final List<Runnable> listeners = new CopyOnWriteArrayList<>();

        @Override
        public ChangeEntries entries(ChangeFilter filter) {
            asked.add(filter);
            List<ChangeEntry> found = rows.stream().filter(row -> (filter.kinds().isEmpty()
                    || filter.kinds().contains(row.kind())) && (filter.sourceId() == null
                    || filter.sourceId().equals(row.sourceId())) && (filter.text() == null
                    || String.valueOf(row.newTitle()).contains(filter.text()))).toList();
            int from = Math.min(filter.offset(), found.size());
            int to = Math.min(found.size(), from + filter.limit());
            return new ChangeEntries(found.subList(from, to), found.size(), filter.offset(), filter.limit());
        }

        @Override
        public Subscription subscribe(Runnable changed) {
            listeners.add(changed);
            return () -> listeners.remove(changed);
        }

        @Override
        public Map<String, String> sources() {
            Map<String, String> sources = new LinkedHashMap<>();
            sources.put("wiki", "Firmenwiki");
            return sources;
        }
    }

    @Test
    void theTableNamesEveryRowAndSearchAndFiltersReachTheCore() throws Exception {
        Log log = new Log();
        log.rows.add(entry("1", ChangeKind.CONTENT_CHANGED, ChangeTrigger.MANUAL, "Handbuch", "Handbuch"));
        log.rows.add(entry("2", ChangeKind.RENAMED, ChangeTrigger.BACKGROUND, "Alt", "Neu"));
        log.rows.add(entry("3", ChangeKind.REMOVED, ChangeTrigger.SCHEDULE, "Weg", null));
        ChangeLogDialog[] dialog = new ChangeLogDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            dialog[0] = new ChangeLogDialog((source, page) -> opened.add(source + "/" + page), () -> log);
            dialog[0].refresh();
        });
        await(() -> dialog[0].rowCount() == 3);
        SwingUtilities.invokeAndWait(() -> {
            ChangeLogDialog d = dialog[0];
            assertThat(d.cell(0, ChangeLogDialog.TIME)).isEqualTo(Texts.time(AT));
            assertThat(d.cell(0, ChangeLogDialog.SOURCE)).as("Anzeigename statt ID").isEqualTo("Firmenwiki");
            assertThat(d.cell(0, ChangeLogDialog.SPACE)).isEqualTo("DEV");
            assertThat(d.cell(0, ChangeLogDialog.PAGE_TITLE)).isEqualTo("Handbuch");
            assertThat(d.cell(0, ChangeLogDialog.KIND)).isEqualTo("Inhalt geändert");
            assertThat(d.cell(0, ChangeLogDialog.TRIGGER)).isEqualTo("Synchronisierung von Hand");
            assertThat(d.cell(1, ChangeLogDialog.PAGE_TITLE)).isEqualTo("Alt → Neu");
            assertThat(d.cell(2, ChangeLogDialog.PAGE_TITLE)).isEqualTo("Weg");
            assertThat(d.count.getText()).isEqualTo("3 Zeilen");
            assertThat(d.more.isVisible()).isFalse();
            assertThat(d.table.isCellEditable(0, ChangeLogDialog.PAGE_TITLE)).as("nur Einsicht").isFalse();
            assertThat(d.source.getItemCount()).as("Alle Quellen und das Firmenwiki").isEqualTo(2);
            assertThat(log.listeners).as("am Core angemeldet, kein Zeitgeber").hasSize(1);
            d.kinds.setSelectedIndex(1 + ChangeKind.RENAMED.ordinal());
        });
        await(() -> dialog[0].rowCount() == 1);
        assertThat(log.asked.get(log.asked.size() - 1).kinds()).containsExactly(ChangeKind.RENAMED);
        // Sichtprüfung, nur mit der Umgebungsvariable CHANGELOG_SCREENSHOT (Pfad der PNG-Datei).
        String screenshot = System.getenv("CHANGELOG_SCREENSHOT");
        if (screenshot != null && !screenshot.isBlank()) {
            SwingUtilities.invokeAndWait(() -> {
                javax.swing.JPanel frame = Dialogs.surface(ChangeLogDialog.TITLE, dialog[0].component(), () -> { });
                frame.setSize(1100, 420);
                for (int pass = 0; pass < 3; pass++) {
                    layoutAll(frame);
                }
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(1100, 420,
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = image.createGraphics();
                frame.paint(g);
                g.dispose();
                try {
                    javax.imageio.ImageIO.write(image, "png", new java.io.File(screenshot));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            return;
        }
        SwingUtilities.invokeAndWait(() -> {
            dialog[0].kinds.setSelectedIndex(0);
            dialog[0].search.setText("Hand");
        });
        await(() -> dialog[0].rowCount() == 1 && "Handbuch".equals(dialog[0].cell(0, ChangeLogDialog.PAGE_TITLE)));
        assertThat(log.asked.get(log.asked.size() - 1).text()).isEqualTo("Hand");
        SwingUtilities.invokeAndWait(() -> dialog[0].search.setText("nichts"));
        await(() -> dialog[0].emptyShown());
        assertThat(dialog[0].emptyText()).isEqualTo(ChangeLogDialog.NO_MATCH);
    }

    @Test
    void moreRowsLoadOnDemandAndRemovedPagesCannotBeOpened() throws Exception {
        Log log = new Log();
        for (int i = 0; i < ChangeLogDialog.PAGE + 20; i++) {
            log.rows.add(entry(String.valueOf(i), ChangeKind.NEW, ChangeTrigger.MANUAL, null, "Seite " + i));
        }
        ChangeLogDialog[] dialog = new ChangeLogDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            dialog[0] = new ChangeLogDialog((source, page) -> opened.add(source + "/" + page), () -> log);
            dialog[0].refresh();
        });
        await(() -> dialog[0].rowCount() == ChangeLogDialog.PAGE);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(dialog[0].count.getText()).isEqualTo("500 von 520 Zeilen");
            assertThat(dialog[0].more.isVisible()).isTrue();
            dialog[0].more.doClick();
        });
        await(() -> dialog[0].rowCount() == ChangeLogDialog.PAGE + 20);
        assertThat(log.asked.get(log.asked.size() - 1).offset()).isEqualTo(ChangeLogDialog.PAGE);
        SwingUtilities.invokeAndWait(() -> dialog[0].openPage(7));
        assertThat(opened).containsExactly("wiki/7");
        assertThat(ChangeLogDialog.openable(entry("1", ChangeKind.REMOVED, ChangeTrigger.MANUAL, "Weg", null)))
                .isFalse();
        assertThat(ChangeLogDialog.openable(entry("1", ChangeKind.WITHHELD, ChangeTrigger.MANUAL, "Weg", null)))
                .isFalse();
        assertThat(ChangeLogDialog.openable(entry("1", ChangeKind.MOVED, ChangeTrigger.MANUAL, "Da", "Da")))
                .isTrue();
    }

    @Test
    void withoutARunningCoreTheDialogSaysThatNothingIsLogged() throws Exception {
        ChangeLogDialog[] dialog = new ChangeLogDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            dialog[0] = new ChangeLogDialog((source, page) -> { }, () -> null);
            dialog[0].refresh();
            assertThat(dialog[0].emptyShown()).isTrue();
            assertThat(dialog[0].emptyText()).isEqualTo(ChangeLogDialog.EMPTY);
        });
    }

    private static void layoutAll(java.awt.Component component) {
        component.doLayout();
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                layoutAll(child);
            }
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        boolean[] met = new boolean[1];
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> met[0] = condition.getAsBoolean());
            if (met[0]) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Bedingung nicht erreicht");
    }
}
