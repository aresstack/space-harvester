package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.GlossaryAccess.Entry;
import com.aresstack.spaceharvester.application.glossary.GlossaryDerivation;
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
        glossary.put(entry("1", "Datenprodukt", "wiki", "DH", RelationOrigin.RULE, ReviewStatus.PROPOSED, 3, false,
                "Ein fachlich abgegrenztes, verantwortetes Datenangebot des Datenhauses."),
                GlossaryDerivation.Category.FACHLICH, new GlossaryDerivation.Importance(14, 41, 6, 2, 0));
        glossary.put(entry("2", "Haushaltsverfahren", null, null, RelationOrigin.MANUAL, ReviewStatus.CONFIRMED, 0,
                false, "Verfahren zur Aufstellung und Bewirtschaftung des Haushalts."),
                GlossaryDerivation.Category.FACHLICH, null);
        glossary.put(entry("3", "Sachbearbeiter", "wiki", null, RelationOrigin.RULE, ReviewStatus.CONFIRMED,
                2, true, null), GlossaryDerivation.Category.ROLLE, new GlossaryDerivation.Importance(9, 23, 12, 0, 0));
        glossary.put(entry("4", "Möglichkeit", "wiki", "DH", RelationOrigin.AI, ReviewStatus.REJECTED, 1, false,
                null), GlossaryDerivation.Category.UNKLAR, null);
        glossary.put(entry("5", "Antragsprüfung", "wiki", "DH", RelationOrigin.AI, ReviewStatus.PROPOSED, 2, false,
                null), GlossaryDerivation.Category.UNKLAR, new GlossaryDerivation.Importance(5, 8, 0, 3, 0));
        glossary.put(entry("6", "Datenbankschema", "wiki", "DH", RelationOrigin.RULE, ReviewStatus.PROPOSED, 2,
                false, null), GlossaryDerivation.Category.TECHNISCH, new GlossaryDerivation.Importance(3, 4, 0, 0, 0));
        GlossaryTab[] tab = new GlossaryTab[1];
        SwingUtilities.invokeAndWait(() -> tab[0] = new GlossaryTab(null, () -> Optional.of(glossary)));
        PillTabs[] tabs = new PillTabs[1];
        SwingUtilities.invokeAndWait(() -> {
            tabs[0] = new PillTabs();
            tabs[0].tab(TermsView.ACRONYMS, new javax.swing.JPanel()).tab(TermsView.IDENTIFIERS,
                    new javax.swing.JPanel()).tab(TermsView.RELATIONS, new javax.swing.JPanel())
                    .tab(TermsView.SYNONYMS, new javax.swing.JPanel()).tab(TermsView.GLOSSARY, tab[0].component())
                    .tab(TermsView.GRAPH, new javax.swing.JPanel())
                    .actions(tab[0].tools(), tab[0].addButton(), tab[0].detectButton());
            tabs[0].select(TermsView.GLOSSARY);
            tab[0].load();
        });
        for (int i = 0; i < 100 && tab[0].directory().table().getRowCount() < 4; i++) {
            Thread.sleep(25);
        }
        for (int width : new int[] {1280, 960, 680}) {
            SwingUtilities.invokeAndWait(() -> {
                javax.swing.JPanel surface = new javax.swing.JPanel(new java.awt.BorderLayout());
                surface.setBackground(Comic.SURFACE);
                surface.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 14, 12, 14));
                surface.add(Comic.plate("Begriffe", Comic.PETROL, tabs[0]), java.awt.BorderLayout.CENTER);
                surface.setSize(width, 460);
                Screenshots.write(surface, out.resolve("fachglossar-" + width + ".png"));
            });
        }
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
