package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Bildschirmfotos des Reiters „Beziehungen“ mit Buchstabenleiste, Art-Chips und Nachladen zur Sichtprüfung (Vorgabe
 * alle Arten; nur Synonym; Synonym und Schreibvariante unter B); läuft nur mit der Umgebungsvariable {@code SCREENSHOTS_DIR}.
 */
class RelationTabScreenshotTest {

    @Test
    void renderRelationTabs() throws Exception {
        String directory = System.getenv("SCREENSHOTS_DIR");
        Assumptions.assumeTrue(directory != null && !directory.isBlank());
        Path out = Path.of(directory);
        Files.createDirectories(out);
        RelationTabTest.FakeCore core = new RelationTabTest.FakeCore();
        core.add("API", "Application Programming Interface", RelationType.ABBREVIATION, ReviewStatus.CONFIRMED);
        core.add("BMF", "Bundesministerium der Finanzen", RelationType.ABBREVIATION, ReviewStatus.PROPOSED);
        core.add("Beleg", "Buchungsbeleg", RelationType.SYNONYM, ReviewStatus.PROPOSED);
        core.add("Bescheid", "Beschluss", RelationType.SYNONYM, ReviewStatus.REJECTED);
        core.add("Zahlung", "Auszahlung", RelationType.SYNONYM, ReviewStatus.CONFIRMED);
        core.add("Antrag", "Anträge", RelationType.VARIANT, ReviewStatus.PROPOSED);
        core.add("E-Mail", "Email", RelationType.VARIANT, ReviewStatus.CONFIRMED);
        core.add("Haushalt", "Haushaltsplan", RelationType.RELATED, ReviewStatus.PROPOSED);
        for (int i = 0; i < 230; i++) {
            core.add(String.format(java.util.Locale.ROOT, "SB%03d", i), "Sachbearbeitung " + i,
                    RelationType.ABBREVIATION, ReviewStatus.PROPOSED);
        }
        RelationTab[] relations = new RelationTab[1];
        RelationTab[] synonyms = new RelationTab[1];
        RelationTab[] both = new RelationTab[1];
        SwingUtilities.invokeAndWait(() -> {
            relations[0] = newTab(core);
            relations[0].load(null);
            synonyms[0] = newTab(core);
            synonyms[0].typeChip(RelationType.SYNONYM).doClick();
            both[0] = newTab(core);
            both[0].typeChip(RelationType.SYNONYM).doClick();
            both[0].typeChip(RelationType.VARIANT).doClick();
            both[0].directory().letterButton("B").doClick();
        });
        for (int i = 0; i < 200 && (relations[0].directory().table().getRowCount() < 200
                || synonyms[0].directory().table().getRowCount() < 3
                || both[0].directory().table().getRowCount() < 2); i++) {
            Thread.sleep(25);
        }
        java.util.Map<RelationTab, String> names = java.util.Map.of(relations[0], "beziehungen-",
                synonyms[0], "beziehungen-synonym-", both[0], "beziehungen-synonym-schreibvariante-b-");
        for (int width : new int[] {1280, 960}) {
            for (RelationTab tab : List.of(relations[0], synonyms[0], both[0])) {
                javax.swing.JPanel[] surface = new javax.swing.JPanel[1];
                SwingUtilities.invokeAndWait(() -> surface[0] = surface(tab, width));
                // Die Größenänderung meldet sich erst im nächsten Ereignis; die Buchstabenleiste rückt dann um.
                SwingUtilities.invokeAndWait(() -> { });
                SwingUtilities.invokeAndWait(() -> Screenshots.write(surface[0], out.resolve(
                        names.get(tab) + width + ".png")));
            }
        }
    }

    private static RelationTab newTab(RelationTabTest.FakeCore core) {
        RelationTab tab = new RelationTab(null, "Beziehungen zwischen Begriffen",
                new String[]{"Begriff", "Beziehung", "Zu Begriff", "Status", "Herkunft"},
                r -> List.of(r.fromTerm(), Relation.typeText(r.type()), r.toTerm(),
                        Relation.statusText(r.status()), Relation.originText(r.origin())),
                List.of(), RelationType.ABBREVIATION, RelationTab.NO_RELATIONS);
        tab.fetch(core);
        return tab;
    }

    private static javax.swing.JPanel surface(RelationTab tab, int width) {
        PillTabs tabs = new PillTabs();
        tabs.tab("Technische Angaben", new javax.swing.JPanel()).tab("Analyse", tab.component())
                .tab("Originaldaten", new javax.swing.JPanel())
                .actions(tab.proposeButton(), tab.createButton());
        tabs.select("Analyse");
        javax.swing.JPanel surface = new javax.swing.JPanel(new java.awt.BorderLayout());
        surface.setBackground(Comic.SURFACE);
        surface.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 14, 12, 14));
        surface.add(Comic.plate("Analyse", Comic.PETROL, tabs), java.awt.BorderLayout.CENTER);
        surface.setSize(width, 520);
        surface.validate();
        return surface;
    }
}
