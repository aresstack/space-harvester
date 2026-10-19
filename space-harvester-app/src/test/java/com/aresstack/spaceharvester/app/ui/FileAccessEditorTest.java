package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SecretVaultTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Platte „Dateizugriff“ wie im Enterprise AI Client: Ordner werden einmal freigegeben und wieder entfernt, „Alle
 * freigeben“ geht nur nach der zweiten Bestätigung an, Abschalten ohne; „Speichern“ schreibt nur
 * {@code local-files.roots} und {@code allow-all} und lässt den Rest der Datei stehen.
 */
class FileAccessEditorTest {

    @TempDir
    Path home;

    private AppContext context;
    private Path config;
    private String before;

    @BeforeEach
    void start() throws Exception {
        config = home.resolve("app.yml");
        before = """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  # Kommentar bleibt stehen
                  sources: []
                """.formatted(home.resolve("data"));
        Files.writeString(config, before);
        SecretVault vault = new SecretVault(home.resolve("secrets"), new SecretVaultTest.FakeDpapi());
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test", vault);
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
    }

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void allowAllOnlyTurnsOnAfterTheSecondConfirmation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicBoolean answer = new AtomicBoolean(false);
            AtomicInteger asked = new AtomicInteger();
            FileAccessEditor editor = new FileAccessEditor(context, parent -> {
                asked.incrementAndGet();
                return answer.get();
            });
            assertThat(editor.allowAll.isSelected()).as("Standard aus").isFalse();

            editor.allowAll.doClick();
            assertThat(editor.allowAll.isSelected()).as("abgelehnt: bleibt aus").isFalse();
            assertThat(asked.get()).isEqualTo(1);
            assertThat(editor.changed()).isFalse();

            answer.set(true);
            editor.allowAll.doClick();
            assertThat(editor.allowAll.isSelected()).isTrue();
            assertThat(asked.get()).isEqualTo(2);
            assertThat(editor.changed()).isTrue();

            editor.allowAll.doClick();
            assertThat(editor.allowAll.isSelected()).as("Abschalten fragt nicht nach").isFalse();
            assertThat(asked.get()).isEqualTo(2);
        });
    }

    @Test
    void foldersAreGrantedOnceRevokedAndSavedWithoutTouchingTheRest() throws Exception {
        String projects = home.resolve("Projekte").toString();
        String team = home.resolve("team wissen").toString();
        var runtime = context.runtime();
        FileAccessEditor[] editor = new FileAccessEditor[1];
        SwingUtilities.invokeAndWait(() -> {
            editor[0] = new FileAccessEditor(context, parent -> true);
            assertThat(editor[0].list().getComponentCount()).as("nur der Hinweis „Noch kein Ordner“").isEqualTo(1);
            editor[0].path.setText("  " + projects + " ");
            editor[0].add.doClick();
            editor[0].path.setText(team);
            editor[0].add.doClick();
            editor[0].path.setText(projects);
            editor[0].add.doClick();
            assertThat(editor[0].roots()).containsExactly(projects, team);
            assertThat(editor[0].list().getComponentCount()).isEqualTo(2);
            assertThat(editor[0].path.getText()).isEmpty();

            editor[0].revoke(projects);
            assertThat(editor[0].roots()).containsExactly(team);
            editor[0].allowAll.doClick();
            editor[0].save();
        });
        await(() -> editor[0].feedback.text().contains("Dateizugriff übernommen"));

        String text = Files.readString(config);
        assertThat(text).startsWith(before.strip().lines().findFirst().orElseThrow())
                .contains("# Kommentar bleibt stehen", "  sources: []", "data-directory: ", "local-files:",
                        "allow-all: true", "roots:", team).doesNotContain(projects);
        assertThat(context.settings().localFiles().allowAll()).isTrue();
        assertThat(context.settings().localFiles().roots()).containsExactly(Path.of(team));
        assertThat(context.runtime()).as("ohne Neustart übernommen").isSameAs(runtime);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(editor[0].changed()).isFalse();
            assertThat(editor[0].roots()).isEqualTo(List.of(team));
        });
    }

    /** Die Datei hält die Pfade normalisiert; nach dem Speichern gilt die Platte trotzdem als unverändert. */
    @Test
    void aFolderTypedWithDotsCountsAsSavedAfterSaving() throws Exception {
        String typed = home.resolve("wissen").toString() + java.io.File.separator + ".";
        FileAccessEditor[] editor = new FileAccessEditor[1];
        SwingUtilities.invokeAndWait(() -> {
            editor[0] = new FileAccessEditor(context, parent -> true);
            editor[0].path.setText(typed);
            editor[0].add.doClick();
            editor[0].save();
        });
        await(() -> editor[0].feedback.text().contains("Dateizugriff übernommen"));
        assertThat(context.settings().localFiles().roots()).containsExactly(home.resolve("wissen"));
        await(() -> !editor[0].changed());
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.currentTimeMillis() + 30_000;
        boolean[] met = {false};
        while (System.currentTimeMillis() < until) {
            SwingUtilities.invokeAndWait(() -> met[0] = condition.getAsBoolean());
            if (met[0]) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Bedingung nicht erfüllt");
    }
}
