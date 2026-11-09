package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Reiter „Beziehungen“ unter Begriffe (#120) mit echtem Core: Vorschläge der Akronym-Regel stehen in der Liste,
 * Bestätigen und Ablehnen gehen mit dem gezeigten Stand an den Core, abgelehnte bleiben sichtbar.
 */
class TermRelationsViewTest {

    @TempDir
    Path home;

    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void proposalsAreListedAndReviewedInTheCore() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("schnittstellen.md"), "# Schnittstellen\n\nDie Application Programming "
                + "Interface (API) nutzt REST. Jede API meldet sich per Single Sign-On (SSO) an.\n");
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
        Workspace workspace = context.runtime().workspace();
        await("Quelle verbunden", () ->
                context.connection("handbuch").state() == AppContext.Connection.State.CONNECTED);
        String space = workspace.spaces("handbuch").get(0).key();
        String job = workspace.startSync("handbuch", space).id();
        for (int i = 0; i < 600 && !workspace.job(job).state().terminal(); i++) {
            Thread.sleep(50);
        }
        assertThat(workspace.job(job).state()).isEqualTo(JobState.COMPLETED);
        // Der Abgleich legt nur den Katalog an; das Lesen der Seite füllt den Begriffsindex.
        String pageId = workspace.liveMetadata("handbuch", space, null, 10).entries().get(0).page().pageId();
        workspace.page("handbuch", pageId, false);
        // Die Akronyme füllt die Anreicherung im Hintergrund, und sie gleicht danach selbst die Regelvorschläge ab.
        // Wer zuerst vorschlägt, ist offen: Gezählt wird der Stand im Core, nicht die Zahl, die ein Lauf hinzufügt.
        awaitOffEdt("zwei Regelvorschläge im Core", () -> {
            workspace.proposeAcronymRelations("handbuch");
            return workspace.termRelations(null, null, 10).relations().size() == 2;
        });

        TermsView[] view = new TermsView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new TermsView(context);
            view[0].shown();
        });
        await("zwei Beziehungen in der Liste", () -> view[0].relations().table().getRowCount() == 2);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(view[0].relations().table().getValueAt(0, 0)).isEqualTo("API");
            assertThat(view[0].relations().table().getValueAt(0, 1)).isEqualTo("Abkürzung für");
            assertThat(view[0].relations().table().getValueAt(0, 3)).isEqualTo("Vorgeschlagen");
            assertThat(view[0].relations().table().getValueAt(0, 4)).isEqualTo("Regel");
            view[0].relations().table().setRowSelectionInterval(0, 0);
            view[0].reviewButtons().get(0).doClick();
        });
        await("API bestätigt", () -> "Bestätigt".equals(view[0].relations().table().getValueAt(0, 3)));

        // Abgelehnt bleibt die Beziehung sichtbar, am Ende der Liste.
        SwingUtilities.invokeAndWait(() -> {
            view[0].relations().table().setRowSelectionInterval(1, 1);
            view[0].reviewButtons().get(1).doClick();
        });
        await("SSO abgelehnt am Ende", () -> view[0].relations().table().getRowCount() == 2
                && "Abgelehnt".equals(view[0].relations().table().getValueAt(1, 3)));
        assertThat(workspace.termRelations(null, null, 10).relations()).hasSize(1);
        // Nach dem Ablehnen bleibt die Zeile gewählt, grau hinterlegt.
        SwingUtilities.invokeAndWait(() -> {
            assertThat(view[0].relations().table().getSelectedRow()).isEqualTo(1);
            java.awt.Component cell = view[0].relations().table().prepareRenderer(
                    view[0].relations().table().getCellRenderer(0, 4), 0, 4);
            assertThat(((javax.swing.JLabel) cell).getHorizontalAlignment()).isEqualTo(javax.swing.SwingConstants.LEFT);
            view[0].createRelation("REST", com.aresstack.spaceharvester.application.terms.RelationType.RELATED,
                    "HTTP");
        });
        await("REST angelegt (drei Zeilen)", () -> view[0].relations().table().getRowCount() == 3);
        int[] row = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            row[0] = view[0].relations().table().getSelectedRow();
            assertThat(view[0].relations().table().getValueAt(row[0], 0)).isEqualTo("REST");
            assertThat(view[0].relations().table().getValueAt(row[0], 3)).isEqualTo("Bestätigt");
            assertThat(view[0].relations().table().getValueAt(row[0], 4)).isEqualTo("Manuell");
            view[0].reviewButtons().get(2).doClick();
        });
        // Entfernt bleibt die Zeile stehen; die Schaltfläche heißt jetzt Rückgängig.
        await("REST entfernt, Rückgängig angeboten", () ->
                TermsView.UNDO.equals(view[0].reviewButtons().get(2).getText()));
        assertThat(workspace.termRelations("REST", null, 10).relations()).isEmpty();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(view[0].relations().table().getRowCount()).isEqualTo(3);
            view[0].reviewButtons().get(2).doClick();
        });
        await("Rückgängig: REST wieder da und gewählt", () ->
                TermsView.REMOVE.equals(view[0].reviewButtons().get(2).getText())
                && view[0].relations().table().getSelectedRow() >= 0);
        assertThat(workspace.termRelations("REST", null, 10).relations()).hasSize(1);
        // Entfernt und neu angelegt steht sie wieder normal da, nicht durchgestrichen.
        SwingUtilities.invokeAndWait(() -> view[0].reviewButtons().get(2).doClick());
        await("REST erneut entfernt", () -> TermsView.UNDO.equals(view[0].reviewButtons().get(2).getText()));
        SwingUtilities.invokeAndWait(() -> view[0].createRelation("REST",
                com.aresstack.spaceharvester.application.terms.RelationType.RELATED, "HTTP"));
        await("REST neu angelegt, nicht mehr entfernt", () ->
                TermsView.REMOVE.equals(view[0].reviewButtons().get(2).getText())
                && view[0].relations().table().getSelectedRow() >= 0);
        SwingUtilities.invokeAndWait(() -> {
            int selected = view[0].relations().table().getSelectedRow();
            assertThat(view[0].relations().table().getValueAt(selected, 0)).isEqualTo("REST");
        });
        assertThat(workspace.termRelations("REST", null, 10).relations()).hasSize(1);
        // Spaltenköpfe sortieren.
        SwingUtilities.invokeAndWait(() -> {
            view[0].relations().table().getRowSorter().toggleSortOrder(0);
            view[0].relations().table().getRowSorter().toggleSortOrder(0);
            assertThat(view[0].relations().table().getValueAt(0, 0)).isEqualTo("SSO");
        });
    }

    @Test
    void synonymsListOnlySynonymsAndVariantsAndTheGraphDrawsTheNeighbourhood() throws Exception {
        Path files = Files.createDirectories(home.resolve("leer"));
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: leer
                      type: files
                      title: Leer
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), files));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        Workspace workspace = context.runtime().workspace();
        for (Object[] r : new Object[][]{
                {"API", RelationType.ABBREVIATION, "Application Programming Interface"},
                {"Sachbearbeitung", RelationType.SYNONYM, "Fallbearbeitung"},
                {"E-Mail", RelationType.VARIANT, "Email"},
                {"Application Programming Interface", RelationType.RELATED, "REST"}}) {
            workspace.addRelation(new TermRelations.NewRelation((String) r[0], (String) r[2], (RelationType) r[1],
                    null, null, null));
        }

        TermsView[] view = new TermsView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new TermsView(context);
            view[0].shown();
        });
        javax.swing.JTable[] synonyms = new javax.swing.JTable[1];
        SwingUtilities.invokeAndWait(() -> synonyms[0] = view[0].synonyms().directory().table());
        await("Synonyme und Beziehungen geladen", () -> synonyms[0].getRowCount() == 2 && view[0].relations().table().getRowCount() == 4);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(synonyms[0].getValueAt(0, 0)).isEqualTo("E-Mail");
            assertThat(synonyms[0].getValueAt(0, 1)).isEqualTo("Email");
            assertThat(synonyms[0].getValueAt(0, 2)).isEqualTo("Schreibvariante");
            assertThat(synonyms[0].getValueAt(1, 2)).isEqualTo("Synonym");
            assertThat(synonyms[0].getValueAt(1, 3)).isEqualTo("Manuell");
            assertThat(synonyms[0].getValueAt(1, 4)).isEqualTo("Bestätigt");
            // Ablehnen im Reiter Synonyme: die Zeile bleibt gewählt und steht als abgelehnt am Ende.
            synonyms[0].setRowSelectionInterval(1, 1);
            view[0].synonyms().reviewButtons().get(1).doClick();
        });
        await("Synonym abgelehnt", () -> synonyms[0].getRowCount() == 2 && "Abgelehnt".equals(synonyms[0].getValueAt(1, 4))
                && synonyms[0].getSelectedRow() == 1);
        // Neu angelegt im Reiter Synonyme erscheint sie dort gewählt.
        SwingUtilities.invokeAndWait(() -> view[0].synonyms().createRelation("Schnittstelle", RelationType.SYNONYM,
                "API"));
        await("Synonym neu angelegt", () -> synonyms[0].getRowCount() == 3 && synonyms[0].getSelectedRow() >= 0
                && "Schnittstelle".equals(synonyms[0].getValueAt(synonyms[0].getSelectedRow(), 0)));

        // Graph: API in der Mitte, Tiefe 2 bringt auch REST; die abgelehnte Beziehung fehlt.
        SwingUtilities.invokeAndWait(() -> {
            view[0].tabs().select(TermsView.GRAPH);
            view[0].graph().showTerm("api");
        });
        await("Graph Tiefe 2", () -> view[0].graph().canvas().labels().size() == 4);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(view[0].graph().canvas().labels()).containsExactly("API",
                    "Application Programming Interface", "Schnittstelle", "REST");
            assertThat(view[0].graph().canvas().graph().edges()).hasSize(3);
            view[0].graph().depthChoice().setSelectedIndex(0);
        });
        await("Graph Tiefe 1", () -> view[0].graph().canvas().labels().size() == 3);
        assertThat(view[0].graph().centre()).isEqualTo("api");
    }

    @Test
    void newRelationsNeedTwoDifferentTerms() {
        assertThat(TermsView.invalidNewRelation(" ", "API")).isNotNull();
        assertThat(TermsView.invalidNewRelation("api", "API ")).isNotNull();
        assertThat(TermsView.invalidNewRelation("REST", "API")).isNull();
    }

    @Test
    void theListUsesTheRelationLimitAndSaysWhenTheCoreHasMore() {
        assertThat(TermsView.RELATION_LIMIT).isEqualTo(
                com.aresstack.spaceharvester.application.terms.TermRelations.MAX_LIMIT);
        assertThat(TermsView.relationsMessage(null, false)).isNull();
        assertThat(TermsView.relationsMessage(null, true)).isEqualTo(TermsView.MORE_RELATIONS);
        assertThat(TermsView.relationsMessage(TermsView.CONFLICT, true))
                .isEqualTo(TermsView.CONFLICT + " " + TermsView.MORE_RELATIONS);
        assertThat(TermsView.relationsMessage(TermsView.CONFLICT, false)).isEqualTo(TermsView.CONFLICT);
    }

    /** Wartet auf einen Zustand; 120 s sind nur die Grenze für einen Hänger (die Anreicherung dauert unter Last). */
    private static void await(String step, BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 2400; i++) {
            boolean[] done = new boolean[1];
            SwingUtilities.invokeAndWait(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Nicht erreicht: " + step);
    }

    /** Wie {@link #await}, aber außerhalb des EDT: für Aufrufe in den Core, die selbst Zeit brauchen. */
    private static void awaitOffEdt(String step, BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 2400; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Nicht erreicht: " + step);
    }
}
