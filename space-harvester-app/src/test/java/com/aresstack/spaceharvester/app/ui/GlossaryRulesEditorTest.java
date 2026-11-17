package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SecretVaultTest;
import com.aresstack.spaceharvester.app.ui.controls.WordTable;
import com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings;
import com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings.Kind;
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
 * Platte „Regeln für das Fachglossar“ (ADR 0032, FL5b, Glossar 3): die feste Liste des Core steht da und lässt sich
 * nur abhaken ({@code exclude}), eigene Wörter kommen dazu ({@code add}); Leerzeichen und Bindestrich sind erlaubt, ein
 * zu kurzes Wortende verweist auf die Liste „ganzes Wort“; nur {@code glossary.rules} wird geschrieben.
 */
class GlossaryRulesEditorTest {

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
    void ownWordsExcludedWordsAndMaxTermsAreWrittenAndReadBack() throws Exception {
        GlossaryRulesEditor[] editor = new GlossaryRulesEditor[1];
        String fixed = GlossaryRulesEditor.defaults(Kind.TECHNICAL_HEADS).get(0);
        SwingUtilities.invokeAndWait(() -> {
            editor[0] = new GlossaryRulesEditor(context);
            WordTable heads = editor[0].tables.get(Kind.TECHNICAL_HEADS);
            assertThat(heads.words()).isEqualTo(GlossaryRulesEditor.defaults(Kind.TECHNICAL_HEADS)).isNotEmpty();
            assertThat(editor[0].changed()).isFalse();
            assertThat(editor[0].maxTerms.getValue()).isEqualTo(GlossaryRuleSettings.DEFAULT_MAX_TERMS);

            // Ein zu kurzes Wortende: Grund in Worten mit Verweis auf die Liste „ganzes Wort“.
            heads.input().setText("LDAP");
            heads.addButton().doClick();
            assertThat(heads.invalidText()).isEqualTo("Unter 5 Buchstaben zählt ein Wort nur als ganzes Wort: "
                    + "bitte unter „Technik: ganzes Wort“ eintragen.");
            assertThat(heads.words()).doesNotContain("ldap");
            // Zeichen außerhalb der Regel: der Grund des Core.
            heads.input().setText("daten/bank");
            heads.addButton().doClick();
            assertThat(heads.invalidText()).startsWith("Nur Wörter aus Buchstaben, Ziffern, Leerzeichen und "
                    + "Bindestrich");
            // Leerzeichen und Bindestrich sind erlaubt, normalisiert wie im Glossar.
            heads.input().setText("  Fach-Register ");
            heads.addButton().doClick();
            assertThat(heads.invalidText()).isBlank();
            assertThat(heads.words()).endsWith("fach-register");
            WordTable whole = editor[0].tables.get(Kind.TECHNICAL_WORDS);
            whole.input().setText("LDAP");
            whole.addButton().doClick();
            assertThat(whole.words()).endsWith("ldap");

            heads.table().setValueAt(false, 0, WordTable.ACTIVE);
            editor[0].maxTerms.setValue(200);
            assertThat(editor[0].changed()).isTrue();
            editor[0].save();
        });
        await(() -> editor[0].feedback.text().contains("übernommen"));

        String text = Files.readString(config);
        assertThat(text).contains("# Kommentar bleibt stehen", "glossary:", "rules:", "max-terms: 200",
                "technical-heads:", "\"fach-register\"", "\"" + fixed + "\"", "technical-words:", "\"ldap\"");
        GlossaryRuleSettings saved = context.settings().terms().glossaryRules();
        assertThat(saved.maxTerms()).isEqualTo(200);
        assertThat(saved.added(Kind.TECHNICAL_HEADS)).containsExactly("fach-register");
        assertThat(saved.excluded(Kind.TECHNICAL_HEADS)).containsExactly(fixed);
        assertThat(saved.added(Kind.TECHNICAL_WORDS)).containsExactly("ldap");
        await(() -> !editor[0].changed());
        SwingUtilities.invokeAndWait(() -> {
            WordTable heads = editor[0].tables.get(Kind.TECHNICAL_HEADS);
            assertThat(heads.words()).as("abgehaktes Wort der festen Liste bleibt sichtbar").contains(fixed);
            assertThat(heads.table().getValueAt(0, WordTable.ACTIVE)).isEqualTo(false);

            for (WordTable table : editor[0].tables.values()) {
                table.reset();
            }
            editor[0].maxTerms.setValue(GlossaryRuleSettings.DEFAULT_MAX_TERMS);
            editor[0].save();
        });
        await(() -> editor[0].feedback.text().contains("übernommen") && !editor[0].changed());

        assertThat(Files.readString(config)).as("Vorgabe heißt: keine Abweichung").contains("rules: {}")
                .doesNotContain("max-terms", "technical-heads");
        assertThat(context.settings().terms().glossaryRules()).isEqualTo(GlossaryRuleSettings.DEFAULT);
    }

    /** Bild der Platte bei 960 Breite, nur mit {@code SCREENSHOTS_DIR}. */
    @Test
    void renderPlate() throws Exception {
        String directory = System.getenv("SCREENSHOTS_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(directory != null && !directory.isBlank());
        Path out = Path.of(directory);
        Files.createDirectories(out);
        SwingUtilities.invokeAndWait(() -> {
            GlossaryRulesEditor editor = new GlossaryRulesEditor(context);
            WordTable heads = editor.tables.get(Kind.TECHNICAL_HEADS);
            heads.input().setText("LDAP");
            heads.addButton().doClick();
            javax.swing.JPanel surface = new javax.swing.JPanel(new java.awt.BorderLayout());
            surface.setBackground(Comic.SURFACE);
            surface.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 14, 12, 14));
            surface.add(Comic.plate(null, Comic.PETROL, editor.component()), java.awt.BorderLayout.CENTER);
            surface.setSize(960, surface.getPreferredSize().height);
            Screenshots.write(surface, out.resolve("glossar-regeln-960.png"));
        });
    }

    @Test
    void theWizardSummaryNamesMaxTermsAndOwnWords() {
        assertThat(GlossaryRulesEditor.summary(GlossaryRuleSettings.DEFAULT))
                .isEqualTo("höchstens 500 Vorschläge je Lauf · Wortlisten wie mitgeliefert");
        assertThat(GlossaryRulesEditor.summary(GlossaryRuleSettings.DEFAULT.withMaxTerms(100)
                .with(Kind.ROLE_WORDS, List.of("pate"), List.of())
                .with(Kind.TECHNICAL_HEADS, List.of(), List.of(GlossaryRulesEditor.defaults(Kind.TECHNICAL_HEADS)
                        .get(0)))))
                .isEqualTo("höchstens 100 Vorschläge je Lauf · 1 eigenes Wort · 1 Wort abgehakt");
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
