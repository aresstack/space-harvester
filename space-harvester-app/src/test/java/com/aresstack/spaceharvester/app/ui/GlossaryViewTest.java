package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.runtime.GlossaryAccess;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
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
 * Der Reiter „Fachglossar“ mit echtem Core: die Sicht kommt aus {@code GlossaryOverview} über die Auswahl links;
 * Anlegen, Ablehnen und Zurücknehmen gehen an das Glossar des Core, abgelehnte stehen auf der Sperrliste (Chip
 * „abgelehnt“), „Fachbegriffe ermitteln“ geht auch ohne Chat-Modell.
 */
class GlossaryViewTest {

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
    void manualEntriesLiveInTheCoreAndRejectedOnesStandOnTheBlockList() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("datenhaus.md"), "# Datenhaus\n\nJedes Datenprodukt hat ein "
                + "Berechtigungsmanagement.\n");
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
        assertThat(workspace.glossary()).isPresent();

        TermsView[] view = new TermsView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new TermsView(context);
            view[0].tabs().select(TermsView.GLOSSARY);
        });
        GlossaryTab tab = view[0].glossary();
        await("leer geladen", () -> GlossaryTab.EMPTY.equals(tab.directory().hint()));
        SwingUtilities.invokeAndWait(() -> {
            assertThat(tab.addButton().isEnabled()).isTrue();
            // ADR 0032, FL5b: die Regelphase „Begriffe ableiten“ läuft auch ohne Chat-Modell.
            assertThat(tab.detectButton().isEnabled()).as("ohne Chat-Modell mit Regelphase").isTrue();
            tab.create(new GlossaryAccess.NewEntry("Datenprodukt", "handbuch", null, "Ein Datenangebot."));
            tab.create(new GlossaryAccess.NewEntry("Haushaltsverfahren", null, null, null));
        });
        await("zwei Einträge", () -> tab.directory().table().getRowCount() == 2);
        SwingUtilities.invokeAndWait(() -> {
            // Ohne Analyse keine Wichtigkeit: beide hinten, alphabetisch, ohne Einordnung „unklar“ (Core).
            assertThat(tab.directory().table().getValueAt(0, 0)).isEqualTo("Datenprodukt");
            assertThat(tab.directory().table().getValueAt(0, 1)).isEqualTo("unklar");
            assertThat(tab.directory().table().getValueAt(0, 2)).isEqualTo(GlossaryTab.NO_IMPORTANCE);
            assertThat(tab.directory().table().getValueAt(0, 3)).isEqualTo("Bestätigt");
            assertThat(tab.directory().table().getValueAt(0, 4)).isEqualTo("Manuell");
            assertThat(tab.status(com.aresstack.spaceharvester.application.terms.ReviewStatus.CONFIRMED).count())
                    .isEqualTo(2);
            // Ablehnen: der Eintrag verlässt die Liste und steht auf der Sperrliste.
            tab.actions(tab.entryAtView(0)).get(1).run().run();
        });
        await("Datenprodukt abgelehnt", () -> tab.directory().table().getRowCount() == 1
                && "Haushaltsverfahren".equals(tab.directory().table().getValueAt(0, 0))
                && tab.status(com.aresstack.spaceharvester.application.terms.ReviewStatus.REJECTED).count() == 1);
        assertThat(workspace.glossary().get().search(new GlossaryAccess.Search(null, null, null, null, null, null,
                10, null)).entries()).extracting(GlossaryAccess.Entry::term).containsExactly("Haushaltsverfahren");
        SwingUtilities.invokeAndWait(() -> GlossaryTabTest.onlyRejected(tab));
        await("Sperrliste", () -> tab.directory().table().getRowCount() == 1
                && "Datenprodukt".equals(tab.directory().table().getValueAt(0, 0))
                && "Abgelehnt".equals(tab.directory().table().getValueAt(0, 3)));
        SwingUtilities.invokeAndWait(() -> tab.actions(tab.entryAtView(0)).get(1).run().run());
        await("Sperrliste leer", () -> tab.directory().table().getRowCount() == 0);
        SwingUtilities.invokeAndWait(() ->
                tab.status(com.aresstack.spaceharvester.application.terms.ReviewStatus.REJECTED).doClick());
        // Ein manueller Eintrag ist nach dem Zurücknehmen wieder bestätigt, ein Vorschlag wäre vorgeschlagen.
        await("zurückgenommen: wieder bestätigt", () -> tab.directory().table().getRowCount() == 2
                && "Datenprodukt".equals(tab.directory().table().getValueAt(0, 0))
                && "Bestätigt".equals(tab.directory().table().getValueAt(0, 3)));
        // Entfernen löscht den manuellen Eintrag im Core.
        SwingUtilities.invokeAndWait(() -> {
            tab.confirmWith((heading, question) -> true);
            tab.actions(tab.entryAtView(0)).get(3).run().run();
        });
        await("nur noch einer", () -> tab.directory().table().getRowCount() == 1);
        assertThat(workspace.glossary().get().search(new GlossaryAccess.Search(null, null, null, null, null, null,
                10, null)).entries()).extracting(GlossaryAccess.Entry::term).containsExactly("Haushaltsverfahren");
    }

    private static void await(String step, BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 1200; i++) {
            boolean[] done = new boolean[1];
            SwingUtilities.invokeAndWait(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Nicht erreicht: " + step);
    }
}
