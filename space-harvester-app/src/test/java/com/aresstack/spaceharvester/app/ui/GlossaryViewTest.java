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
 * Der Reiter „Fachglossar“ mit echtem Core: Anlegen, Ablehnen und Zurücknehmen gehen an das Glossar des Core, „Alle“
 * zeigt die abgelehnten hinter den offenen, ohne KI-Dienst bleibt „Fachbegriffe ermitteln“ gesperrt.
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
    void manualEntriesLiveInTheCoreAndRejectedOnesFollowTheOpenOnes() throws Exception {
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
            assertThat(tab.detectButton().isEnabled()).as("ohne Chat-Modell gesperrt").isFalse();
            tab.create(new GlossaryAccess.NewEntry("Datenprodukt", "handbuch", null, "Ein Datenangebot."));
            tab.create(new GlossaryAccess.NewEntry("Haushaltsverfahren", null, null, null));
        });
        await("zwei Einträge", () -> tab.directory().table().getRowCount() == 2);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(tab.directory().table().getValueAt(0, 0)).isEqualTo("Datenprodukt");
            assertThat(tab.directory().table().getValueAt(0, 1)).isEqualTo("handbuch");
            assertThat(tab.directory().table().getValueAt(0, 2)).isEqualTo("Manuell");
            assertThat(tab.directory().table().getValueAt(0, 3)).isEqualTo("Bestätigt");
            assertThat(tab.directory().table().getValueAt(1, 1)).isEqualTo("Alle Quellen");
            // Ablehnen: der Eintrag rückt grau ans Ende, hinter die offenen.
            tab.actions(tab.entryAtView(0)).get(1).run().run();
        });
        await("Datenprodukt abgelehnt am Ende", () -> tab.directory().table().getRowCount() == 2
                && "Datenprodukt".equals(tab.directory().table().getValueAt(1, 0))
                && "Abgelehnt".equals(tab.directory().table().getValueAt(1, 3)));
        assertThat(workspace.glossary().get().search(new GlossaryAccess.Search(null, null, null, null, null, null,
                10, null)).entries()).extracting(GlossaryAccess.Entry::term).containsExactly("Haushaltsverfahren");
        SwingUtilities.invokeAndWait(() -> tab.actions(tab.entryAtView(1)).get(1).run().run());
        // Ein manueller Eintrag ist nach dem Zurücknehmen wieder bestätigt, ein KI-Vorschlag wäre vorgeschlagen.
        await("zurückgenommen: wieder bestätigt", () ->
                "Bestätigt".equals(tab.directory().table().getValueAt(0, 3))
                        && "Datenprodukt".equals(tab.directory().table().getValueAt(0, 0)));
        // Entfernen löscht den manuellen Eintrag im Core.
        SwingUtilities.invokeAndWait(() -> tab.actions(tab.entryAtView(0)).get(3).run().run());
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
