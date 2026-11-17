package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.GlossaryAccess;
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
                GlossaryDerivation.Category.UNKLAR, new GlossaryDerivation.Importance(14, 41, 6, 2, 0));
        glossary.put(entry("2", "Haushaltsverfahren", null, null, RelationOrigin.MANUAL, ReviewStatus.CONFIRMED, 0,
                false, "Verfahren zur Aufstellung und Bewirtschaftung des Haushalts."),
                GlossaryDerivation.Category.FACHLICH, null);
        glossary.put(entry("3", "Sachbearbeiter", "wiki", null, RelationOrigin.RULE, ReviewStatus.CONFIRMED,
                2, true, null), GlossaryDerivation.Category.ROLLE, new GlossaryDerivation.Importance(9, 23, 12, 0, 0));
        glossary.put(entry("4", "Möglichkeit", "wiki", "DH", RelationOrigin.AI, ReviewStatus.REJECTED, 1, false,
                null), GlossaryDerivation.Category.UNKLAR, null);
        glossary.put(entry("5", "Antragsprüfung", "wiki", "DH", RelationOrigin.RULE, ReviewStatus.PROPOSED, 2, false,
                null), GlossaryDerivation.Category.UNKLAR, new GlossaryDerivation.Importance(5, 8, 0, 3, 0));
        // Wie der Core ohne Modell: die meisten Begriffe bleiben nach Regel „unklar“, „fachlich“ nur von Hand/KI.
        glossary.put(entry("7", "Zuständigkeit", "wiki", "DH", RelationOrigin.RULE, ReviewStatus.PROPOSED, 2, false,
                null), GlossaryDerivation.Category.UNKLAR, new GlossaryDerivation.Importance(7, 11, 2, 1, 0));
        glossary.put(entry("8", "Datenhaus", "wiki", "DH", RelationOrigin.RULE, ReviewStatus.PROPOSED, 3, false,
                null), GlossaryDerivation.Category.UNKLAR, new GlossaryDerivation.Importance(11, 30, 4, 0, 2));
        glossary.put(entry("6", "Datenbankschema", "wiki", "DH", RelationOrigin.RULE, ReviewStatus.PROPOSED, 2,
                false, null), GlossaryDerivation.Category.TECHNISCH, new GlossaryDerivation.Importance(3, 4, 0, 0, 0));
        GlossaryTab[] tab = new GlossaryTab[1];
        SwingUtilities.invokeAndWait(() -> tab[0] = new GlossaryTab(null, () -> Optional.of(glossary)));
        PillTabs[] tabs = new PillTabs[1];
        SwingUtilities.invokeAndWait(() -> {
            tabs[0] = new PillTabs();
            tabs[0].tab(TermsView.ACRONYMS, new javax.swing.JPanel()).tab(TermsView.IDENTIFIERS,
                    new javax.swing.JPanel()).tab(TermsView.RELATIONS, new javax.swing.JPanel())
                    .tab(TermsView.GLOSSARY, tab[0].component())
                    .tab(TermsView.GRAPH, new javax.swing.JPanel())
                    .actions(tab[0].tools(), tab[0].addButton(), tab[0].detectButton());
            tabs[0].select(TermsView.GLOSSARY);
            // Technik einblenden, damit das Technikwort nach Regel im Bild steht (Vorgabe: aus).
            tab[0].category(GlossaryDerivation.Category.TECHNISCH).setSelected(true);
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
        SwingUtilities.invokeAndWait(() -> {
            Entry entry = tab[0].entryAtView(0);
            GlossaryAccess.Row row = new GlossaryAccess.Row(entry, new GlossaryDerivation.Importance(14, 41, 6, 2,
                    0), GlossaryDerivation.Category.UNKLAR, new com.aresstack.spaceharvester.application.glossary
                    .GlossaryEntry.Classification(GlossaryDerivation.Category.UNKLAR, com.aresstack.spaceharvester
                    .application.glossary.GlossaryEntry.ClassifiedBy.RULE, null), entry.evidence().get(0));
            com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Origin original =
                    com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Origin.ORIGINAL;
            GlossaryDerivation.Term term = new GlossaryDerivation.Term("Datenprodukt", "datenprodukt", false,
                    row.importance(), List.of(new GlossaryDerivation.Use("liefern", original, false, 6, List.of()),
                    new GlossaryDerivation.Use("veröffentlichen", original, false, 2, List.of())),
                    List.of(new GlossaryDerivation.Use("prüfen", original, false, 3, List.of())),
                    List.of(new GlossaryDerivation.Use("verantwortet", original, false, 4, List.of())),
                    List.of(new GlossaryDerivation.Use("Datenangebot", original, false, 2, List.of(),
                            GlossaryDerivation.Relation.BESTIMMUNG, 2)), List.of(),
                    GlossaryDerivation.Category.UNKLAR, GlossaryDerivation.CategoryRule.NONE);
            com.aresstack.spaceharvester.app.ui.controls.InfoPopup popup =
                    new com.aresstack.spaceharvester.app.ui.controls.InfoPopup();
            popup.text(entry.term(), GlossaryTab.overlayText(entry, row, term, List.of(
                    "1. Datenhaus: … jedes Datenprodukt hat einen Verantwortlichen …",
                    "2. Katalog: … das Datenprodukt liefert Kennzahlen …"), List.of(
                    new GlossaryAccess.History("propose", "rule:glossary", T, ReviewStatus.PROPOSED))));
            popup.controls(tab[0].overlayControls(popup, entry, row,
                    "Ein Datenprodukt ist ein verantwortetes Datenangebot des Datenhauses."));
            popup.link("Zu Beleg 1", () -> { }).link("Zu Beleg 2", () -> { })
                    .link(GlossaryTab.DEFINITION_LINK, () -> { });
            java.awt.Dimension size = popup.sizeFor(600);
            popup.setSize(size);
            popup.doLayout();
            layout(popup);
            Screenshots.write(popup, out.resolve("fachglossar-overlay.png"));
        });
        // Eine Einordnung durch die KI mit Grund: „fachlich · von der KI (fachlicher Ablauf)“.
        SwingUtilities.invokeAndWait(() -> {
            Entry entry = entry("5", "Antragsprüfung", "wiki", "DH", RelationOrigin.RULE, ReviewStatus.PROPOSED, 2,
                    false, null);
            GlossaryAccess.Row row = new GlossaryAccess.Row(entry, new GlossaryDerivation.Importance(5, 8, 0, 3, 0),
                    GlossaryDerivation.Category.FACHLICH, new com.aresstack.spaceharvester.application.glossary
                    .GlossaryEntry.Classification(GlossaryDerivation.Category.FACHLICH, com.aresstack.spaceharvester
                    .application.glossary.GlossaryEntry.ClassifiedBy.AI, "DOMAIN_PROCESS"), null);
            com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Origin original =
                    com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Origin.ORIGINAL;
            GlossaryDerivation.Term term = new GlossaryDerivation.Term("Antragsprüfung", "antragsprüfung", false,
                    row.importance(), List.of(), List.of(new GlossaryDerivation.Use("abschließen", original, false, 3,
                    List.of())), List.of(), List.of(), List.of(), GlossaryDerivation.Category.FACHLICH,
                    GlossaryDerivation.CategoryRule.NONE);
            com.aresstack.spaceharvester.app.ui.controls.InfoPopup popup =
                    new com.aresstack.spaceharvester.app.ui.controls.InfoPopup();
            popup.text(entry.term(), GlossaryTab.overlayText(entry, row, term, List.of(
                    "1. Antragsverfahren: … die Antragsprüfung ist innerhalb von vier Wochen abzuschließen …",
                    "2. Ablauf: … nach der Antragsprüfung folgt der Bescheid …"), List.of(
                    new GlossaryAccess.History("propose", "rule:glossary", T, ReviewStatus.PROPOSED),
                    new GlossaryAccess.History("classify", "ai:glossary", T.plusSeconds(600),
                            ReviewStatus.PROPOSED))));
            popup.controls(tab[0].overlayControls(popup, entry, row, null));
            popup.link("Zu Beleg 1", () -> { }).link("Zu Beleg 2", () -> { });
            java.awt.Dimension size = popup.sizeFor(600);
            popup.setSize(size);
            popup.doLayout();
            layout(popup);
            Screenshots.write(popup, out.resolve("fachglossar-overlay-ki.png"));
        });
    }

    private static void layout(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container inner) {
                layout(inner);
            }
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
