package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SecretVaultTest;
import com.aresstack.spaceharvester.app.ui.controls.WordTable;
import com.aresstack.spaceharvester.application.configuration.StandingSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Platte „Wörter für den Stand“ (ADR 0032, S5): Vorgabewörter stehen immer da und lassen sich nur abhaken, eigene
 * Wörter kommen dazu und gehen mit ✕; „Alle aus“ schreibt „-“, „Vorgabe“ entfernt den Schlüssel.
 */
class StandingWordsEditorTest {

    @TempDir
    Path home;

    private AppContext context;
    private Path config;

    @BeforeEach
    void start() throws Exception {
        config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  # Kommentar bleibt stehen
                  sources: []
                """.formatted(home.resolve("data")));
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
    void ownWordsAllOffAndResetAreWrittenAndReadBack() throws Exception {
        StandingWordsEditor[] editor = new StandingWordsEditor[1];
        SwingUtilities.invokeAndWait(() -> {
            editor[0] = new StandingWordsEditor(context);
            WordTable outdated = editor[0].outdated;
            assertThat(outdated.words()).isEqualTo(StandingSettings.DEFAULT_OUTDATED_WORDS);
            assertThat(outdated.table().getValueAt(0, WordTable.ACTIVE)).isEqualTo(true);
            assertThat(editor[0].changed()).isFalse();

            outdated.input().setText("Alt-Stand");
            outdated.addButton().doClick();
            assertThat(outdated.invalidText()).isEqualTo(WordTable.INVALID);
            outdated.input().setText("Altbestand");
            outdated.addButton().doClick();
            assertThat(outdated.words()).endsWith("altbestand");
            assertThat(outdated.input().getText()).isEmpty();

            // Ein Vorgabewort lässt sich nur abhaken, nicht löschen.
            outdated.remove("archiv");
            assertThat(outdated.words()).contains("archiv");
            outdated.table().setValueAt(false, 0, WordTable.ACTIVE);
            assertThat(outdated.active()).doesNotContain("archiv").contains("altbestand");

            // „Alle aus“ fragt mit „Wirklich …“; bei Nein bleibt alles.
            java.util.List<String> asked = new java.util.ArrayList<>();
            editor[0].draft.confirmWith((heading, question) -> {
                asked.add(question);
                return false;
            });
            editor[0].draft.allOffButton().doClick();
            assertThat(asked).containsExactly(WordTable.ALL_OFF_QUESTION);
            assertThat(editor[0].draft.active()).isEqualTo(StandingSettings.DEFAULT_DRAFT_WORDS);
            editor[0].draft.confirmWith((heading, question) -> true);
            editor[0].draft.allOffButton().doClick();
            assertThat(editor[0].draft.active()).isEmpty();
            assertThat(editor[0].outdated.excluded()).containsExactly("archiv");
            assertThat(editor[0].outdated.added()).containsExactly("altbestand");
            assertThat(editor[0].changed()).isTrue();
            editor[0].save();
        });
        await(() -> editor[0].feedback.text().contains("übernommen"));

        String text = Files.readString(config);
        assertThat(text).contains("# Kommentar bleibt stehen", "draft-words: [\"-\"]", "\"altbestand\"")
                .doesNotContain("\"archiv\"");
        StandingSettings saved = context.settings().standing();
        assertThat(saved.draftWords()).isEmpty();
        assertThat(saved.outdatedWords()).contains("altbestand").doesNotContain("archiv");
        await(() -> !editor[0].changed());
        SwingUtilities.invokeAndWait(() -> {
            assertThat(editor[0].outdated.words()).as("abgehaktes Vorgabewort bleibt sichtbar").contains("archiv");
            assertThat(editor[0].draft.table().getValueAt(0, WordTable.ACTIVE)).isEqualTo(false);

            editor[0].outdated.reset();
            editor[0].draft.reset();
            assertThat(editor[0].outdated.words()).isEqualTo(StandingSettings.DEFAULT_OUTDATED_WORDS);
            editor[0].save();
        });
        await(() -> editor[0].feedback.text().contains("übernommen") && !editor[0].changed());

        assertThat(Files.readString(config)).as("Vorgabe heißt: kein Schlüssel").contains("standing: {}")
                .doesNotContain("outdated-words", "draft-words");
        assertThat(context.settings().standing()).isEqualTo(StandingSettings.DEFAULT);
    }

    @Test
    void theWizardSummaryNamesDefaultsAndOwnWords() {
        assertThat(StandingWordsEditor.summary(StandingSettings.DEFAULT)).isEqualTo("überholt: Vorgabe (9 Wörter) · "
                + "Entwurf: Vorgabe (5 Wörter)");
        assertThat(StandingWordsEditor.summary(new StandingSettings(List.of("alt"), List.of())))
                .isEqualTo("überholt: 1 Wort: alt · Entwurf: keine Wörter");
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
