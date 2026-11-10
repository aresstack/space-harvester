package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.GlossaryAccess.Entry;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Bildschirmfotos des Reiters „Fachglossar“ zur Sichtprüfung (Liste, Schleier mit Fortschritt); läuft nur mit der
 * Umgebungsvariable {@code SCREENSHOTS_DIR}.
 */
class GlossaryTabScreenshotTest {

    private static final Instant T = Instant.parse("2026-11-10T08:00:00Z");

    @Test
    void renderGlossaryTab() throws Exception {
        String directory = System.getenv("SCREENSHOTS_DIR");
        Assumptions.assumeTrue(directory != null && !directory.isBlank());
        Path out = Path.of(directory);
        Files.createDirectories(out);
        GlossaryTabTest.FakeGlossary glossary = new GlossaryTabTest.FakeGlossary();
        glossary.aiAvailable = true;
        glossary.put(entry("1", "Datenprodukt", "wiki", "DH", RelationOrigin.AI, ReviewStatus.PROPOSED, 3, false,
                "Ein fachlich abgegrenztes, verantwortetes Datenangebot des Datenhauses."));
        glossary.put(entry("2", "Haushaltsverfahren", null, null, RelationOrigin.MANUAL, ReviewStatus.CONFIRMED, 0,
                false, "Verfahren zur Aufstellung und Bewirtschaftung des Haushalts."));
        glossary.put(entry("3", "Berechtigungsmanagement", "wiki", null, RelationOrigin.AI, ReviewStatus.CONFIRMED,
                2, true, null));
        glossary.put(entry("4", "Möglichkeit", "wiki", "DH", RelationOrigin.AI, ReviewStatus.REJECTED, 1, false,
                null));
        GlossaryTab[] tab = new GlossaryTab[1];
        SwingUtilities.invokeAndWait(() -> tab[0] = new GlossaryTab(null, () -> Optional.of(glossary)));
        PillTabs[] tabs = new PillTabs[1];
        SwingUtilities.invokeAndWait(() -> {
            tabs[0] = new PillTabs();
            tabs[0].tab(TermsView.ACRONYMS, new javax.swing.JPanel()).tab(TermsView.IDENTIFIERS,
                    new javax.swing.JPanel()).tab(TermsView.RELATIONS, new javax.swing.JPanel())
                    .tab(TermsView.SYNONYMS, new javax.swing.JPanel()).tab(TermsView.GLOSSARY, tab[0].component())
                    .tab(TermsView.GRAPH, new javax.swing.JPanel())
                    .actions(tab[0].addButton(), tab[0].detectButton());
            tabs[0].select(TermsView.GLOSSARY);
            tab[0].load();
        });
        for (int i = 0; i < 100 && tab[0].directory().table().getRowCount() < 4; i++) {
            Thread.sleep(25);
        }
        javax.swing.JPanel[] surface = new javax.swing.JPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            surface[0] = new javax.swing.JPanel(new java.awt.BorderLayout());
            surface[0].setBackground(Comic.SURFACE);
            surface[0].setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 14, 12, 14));
            surface[0].add(Comic.plate("Begriffe", Comic.PETROL, tabs[0]), java.awt.BorderLayout.CENTER);
            surface[0].setSize(1180, 520);
            Screenshots.write(surface[0], out.resolve("fachglossar.png"));
            tab[0].overlay().setText(GlossaryTab.progressText(new JobProgress(40, 12, 3, 0), false));
            tab[0].overlay().onCancel(() -> { });
            tab[0].overlay().setBusy(true);
            Screenshots.write(surface[0], out.resolve("fachglossar-lauf.png"));
            tab[0].overlay().setBusy(false);
        });
    }

    private static Entry entry(String id, String term, String sourceId, String spaceKey, RelationOrigin origin,
                               ReviewStatus status, int evidence, boolean stale, String explanation) {
        List<RelationEvidence> proofs = new java.util.ArrayList<>();
        for (int i = 0; i < evidence; i++) {
            proofs.add(new RelationEvidence("wiki", "p" + i, 10, 20, "sha" + i));
        }
        return new Entry(id, term, term.toLowerCase(java.util.Locale.GERMAN), sourceId, spaceKey, origin, status,
                proofs, stale, explanation, origin == RelationOrigin.AI ? "gpt-4o/p1" : null, T, T,
                status == ReviewStatus.PROPOSED ? null : T, 1);
    }
}
