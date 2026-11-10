package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.ConsoleOutput;
import com.aresstack.spaceharvester.app.settings.ViewerBackground;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SecretVaultTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der Einrichtungsassistent: einmal beim Start, danach über die Einstellungen; Schritte wie im EAC. */
class SetupWizardTest {

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
                  sources:
                    # Kommentar bleibt stehen
                    - id: wiki
                      type: confluence
                      title: Firmenwiki
                      base-url: https://wiki.example.invalid
                      auth:
                        type: none
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
    void theShipWalksThroughTheStepsAndTheSummaryShowsTheSource() throws Exception {
        SetupWizard[] wizard = new SetupWizard[1];
        SwingUtilities.invokeAndWait(() -> wizard[0] = new SetupWizard(context));
        SwingUtilities.invokeAndWait(() -> {
            assertThat(wizard[0].step()).isZero();
            assertThat(wizard[0].back.isEnabled()).isFalse();
            assertThat(wizard[0].finish.isVisible()).isFalse();
            for (int i = 1; i < SetupWizard.STEPS.length; i++) {
                wizard[0].next.doClick();
                assertThat(wizard[0].step()).isEqualTo(i);
            }
            assertThat(wizard[0].finish.isVisible()).isTrue();
            assertThat(wizard[0].next.isVisible()).isFalse();
            assertThat(wizard[0].trail.current()).isEqualTo(SetupWizard.STEPS.length - 1);
        });
        assertThat(SetupWizard.LINES).hasSameSizeAs(SetupWizard.STEPS);
        assertThat(SetupWizard.STEPS).containsExactly("Wissensquellen", "Netzwerk", "KI-Dienst", "Modelle",
                "Abschluss");
        assertThat(texts(wizard[0].summary)).contains("Firmenwiki (wiki)", "keine Quelle", ParserModelSetup.LABEL,
                "KeePass", "nicht gekoppelt");
        assertThat(texts(wizard[0].summary).stream().filter(t -> t.startsWith("Installiert")
                || t.startsWith("Nicht installiert") || t.startsWith("Nicht verfügbar")))
                .as("Stand des Parsermodells auf der Abschlussseite").isNotEmpty();
        assertThat(texts(wizard[0].sources)).contains("Firmenwiki (wiki)");
        // Ansehen ist kein Einrichten: Die Quelle ist nicht erreichbar, kein KI-Dienst, also kein Häkchen.
        SwingUtilities.invokeAndWait(() -> assertThat(wizard[0].done()).containsOnly(false));
    }

    @Test
    void everyStepCanScrollVerticallyWithoutHidingNavigation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SetupWizard wizard = new SetupWizard(context);
            java.util.List<javax.swing.JScrollPane> pages = SourceFormLayoutTest.find(
                    wizard.component(), javax.swing.JScrollPane.class).stream()
                    .filter(scroll -> scroll.getVerticalScrollBarPolicy()
                            == javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS)
                    .toList();

            assertThat(pages).hasSize(SetupWizard.STEPS.length);
            for (javax.swing.JScrollPane page : pages) {
                assertThat(page.getViewport().getView()).isInstanceOf(javax.swing.Scrollable.class);
                assertThat(page.getHorizontalScrollBarPolicy())
                        .isEqualTo(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
                assertThat(javax.swing.SwingUtilities.isDescendingFrom(wizard.next, page)).isFalse();
                assertThat(javax.swing.SwingUtilities.isDescendingFrom(wizard.finish, page)).isFalse();
            }
        });
    }

    @Test
    void theNetworkTestTriesTheSourceAndSaysClearlyThatItFailed() throws Exception {
        SetupWizard[] wizard = new SetupWizard[1];
        CountDownLatch tested = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            wizard[0] = new SetupWizard(context);
            wizard[0].show(SetupWizard.NETWORK);
            wizard[0].testConnections(tested::countDown);
        });
        assertThat(tested.await(60, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(texts(wizard[0].tests)).anyMatch(text -> text.startsWith("Verbindung fehlgeschlagen"));
            assertThat(wizard[0].feedback.text()).contains("Verbindung fehlgeschlagen");
            assertThat(wizard[0].done()[SetupWizard.NETWORK]).isFalse();
        });
    }

    @Test
    void closingTheWizardRemembersItInTheFileAndKeepsTheRest() throws Exception {
        assertThat(SetupWizard.due(context)).isTrue();
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> context.setupCompleted(done::countDown, error -> {
            failure[0] = error;
            done.countDown();
        }));
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(failure[0]).isNull();
        assertThat(SetupWizard.due(context)).isFalse();
        String text = Files.readString(config, StandardCharsets.UTF_8);
        assertThat(text).contains("  setup-completed: true", "# Kommentar bleibt stehen", "title: Firmenwiki");
        assertThat(context.loader().read(config, text).setupCompleted()).isTrue();
    }

    @Test
    void theTerminalOutputIsChosenUnderGeneralSavedAtOnceAndShownOnTheSummary() throws Exception {
        SettingsView[] view = new SettingsView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SettingsView(context);
            java.util.List<String> items = new java.util.ArrayList<>();
            for (int i = 0; i < view[0].console.getItemCount(); i++) {
                items.add(view[0].console.getItemAt(i));
            }
            assertThat(items).containsExactly("Aus", "Fehler", "Warnungen", "Informationen", "Alles");
            assertThat(view[0].console.getSelectedItem()).as("ohne Angabe still").isEqualTo("Aus");
            view[0].console.setSelectedItem("Warnungen");
            // Angelo 17.11.2026: Platte „Darstellung“ unter Allgemein, Hintergrund des Bildbetrachters.
            java.util.List<String> backgrounds = new java.util.ArrayList<>();
            for (int i = 0; i < view[0].viewer.getItemCount(); i++) {
                backgrounds.add(view[0].viewer.getItemAt(i));
            }
            assertThat(backgrounds).containsExactly("Wie die App", "Weiß", "Grau", "Dunkel", "Schwarz");
            assertThat(view[0].viewer.getSelectedItem()).as("ohne Angabe wie die App").isEqualTo("Wie die App");
            view[0].viewer.setSelectedItem("Schwarz");
        });
        await(() -> context.settings().consoleOutput() == ConsoleOutput.WARN);
        await(() -> context.settings().viewerBackground() == ViewerBackground.BLACK);
        assertThat(Files.readString(config, StandardCharsets.UTF_8))
                .contains("logging:\n  threshold:\n    console: \"warn\"\n", "# Kommentar bleibt stehen",
                        "  viewer:\n    background: black\n");
        SetupWizard[] wizard = new SetupWizard[1];
        SwingUtilities.invokeAndWait(() -> {
            assertThat(view[0].console.getSelectedItem()).isEqualTo("Warnungen");
            assertThat(view[0].viewer.getSelectedItem()).isEqualTo("Schwarz");
            wizard[0] = new SetupWizard(context);
            wizard[0].show(SetupWizard.SUMMARY);
            assertThat(texts(wizard[0].summary)).containsSubsequence(SettingsView.CONSOLE_TITLE, "Warnungen",
                    SettingsView.VIEWER_TITLE, "Schwarz");
        });
    }

    @Test
    void theAnalyticsExportIsEditedUnderGeneralWithTheDefaultsAsPlaceholdersAndShownOnTheSummary()
            throws Exception {
        SettingsView[] view = new SettingsView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SettingsView(context);
            assertThat(view[0].analyticsDetails.getText()).as("ohne Angabe leer, die Vorgabe ist Platzhalter")
                    .isEmpty();
            assertThat(view[0].analyticsContentViews.placeholder()).startsWith("/rest/confanalytics/1.0/space/"
                    + "paginated/contentViews?");
            assertThat(view[0].analyticsPeriod.value()).isEqualTo(java.time.Duration.ofDays(365));
            view[0].analyticsContentViews.setText("/rest/confanalytics/2.0/space/paginated/contentViews?spaceKey="
                    + "{spaceKey}");
            view[0].analyticsContentViews.postActionEvent();
        });
        await(() -> context.settings().analytics().contentViews() != null);
        assertThat(context.settings().analytics().contentViews()).startsWith("/rest/confanalytics/2.0/");
        assertThat(Files.readString(config, StandardCharsets.UTF_8)).contains("  analytics:\n    content-views: "
                + "\"/rest/confanalytics/2.0/space/paginated/contentViews?spaceKey={spaceKey}\"\n",
                "# Kommentar bleibt stehen");
        SwingUtilities.invokeAndWait(() -> {
            // Ein Pfad außerhalb von /rest/ wird gemeldet, nicht gespeichert.
            view[0].analyticsDetails.setText("https://example.org/rest/x");
            view[0].analyticsDetails.postActionEvent();
            assertThat(context.settings().analytics().details()).isNull();
            SetupWizard wizard = new SetupWizard(context);
            wizard.show(SetupWizard.SUMMARY);
            assertThat(texts(wizard.summary)).containsSubsequence(SettingsView.ANALYTICS_TITLE, "angepasst · 365 Tage");
        });
    }

    @Test
    void theSecurityTabNamesWhereSecretsLiveButNeverTheirValues() throws Exception {
        SettingsView[] view = new SettingsView[1];
        // Gelesen wird auf dem EDT: Meldet die Laufzeit inzwischen einen neuen Stand (etwa eine pausierte Quelle),
        // baut SettingsView die Seite dort neu auf; von einem anderen Thread aus sähe der Test sie halb leer.
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SettingsView(context);
            assertThat(texts(view[0].security)).contains("Tresor", "API-Key des KI-Dienstes",
                    "kein KI-Dienst eingerichtet", "Zugang Firmenwiki", "keins nötig");
            // Ohne lokal gespeicherte Zugangsdaten gibt es nichts zu löschen.
            assertThat(view[0].deleteButtons).isEmpty();
        });
    }

    @Test
    void theSecurityTabShowsKeePassAsOriginInsteadOfMissing() throws Exception {
        // Quelle und KI-Dienst beziehen ihre Zugangsdaten über KeePass: nichts liegt lokal, also weder „fehlt“ noch
        // ein Papierkorb (Angelo, 09.11., beta.310: „Zugang … fehlt“ trotz funktionierender Anmeldung).
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                  ai-access: keepass
                  ai-keepass-entry: Enterprise AI API
                  source-keepass-entries:
                    wiki: Wiki-Zugang
                space-harvester:
                  ai:
                    base-url: https://kp.example.invalid/v1
                  sources:
                    - id: wiki
                      type: confluence
                      title: Firmenwiki
                      base-url: https://wiki.example.invalid
                      auth:
                        type: pat
                        token: ${WIKI_TOKEN}
                """.formatted(home.resolve("data")));
        CountDownLatch reloaded = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(reloaded::countDown));
        assertThat(reloaded.await(30, TimeUnit.SECONDS)).isTrue();
        SettingsView[] view = new SettingsView[1];
        // Auf dem EDT gelesen, wie im Test oben: ein Neuaufbau der Seite läuft dort und nie mitten in die Prüfung.
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SettingsView(context);
            java.util.List<String> texts = texts(view[0].security);
            assertThat(texts).contains("Zugang Firmenwiki", "API-Key des KI-Dienstes")
                    .noneMatch(text -> text.equals("fehlt") || text.equals("gespeichert"));
            assertThat(texts.stream().filter(text -> text.startsWith("in KeePass („Wiki-Zugang“"))).hasSize(1);
            assertThat(texts.stream().filter(text -> text.startsWith("in KeePass („Enterprise AI API“")))
                    .hasSize(1);
            assertThat(view[0].deleteButtons).as("aus KeePass: nichts Lokales zu löschen").isEmpty();
            assertThat(tooltips(view[0].security)).anyMatch(tip -> tip != null && tip.contains("nur in KeePass")
                    && tip.contains("Entkoppeln"));
        });
    }

    @Test
    void theSecurityTabPairsAndUnpairsKeePassAndTheAiTabFollows() throws Exception {
        SettingDialogsTest.FakeKeePass keePass = new SettingDialogsTest.FakeKeePass();
        keePass.entries.put("Enterprise AI API", new String[] {"https://kp.example.invalid/v1", "sk-aus-keepass"});
        boolean[] paired = {false};
        context.keePass(new com.aresstack.spaceharvester.app.credentials.KeePassLink(keePass, () -> paired[0],
                () -> paired[0] = false));
        SettingsView[] view = new SettingsView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SettingsView(context);
            assertThat(view[0].keePass.status.getText()).isEqualTo(KeePassPairingPanel.NOT_PAIRED);
            assertThat(view[0].keePass.pair.getText()).isEqualTo(KeePassPairingPanel.PAIR);
            assertThat(view[0].keePass.unpair.isVisible()).as("Entkoppeln nur, wenn gekoppelt").isFalse();
            // Der Reiter „KI-Dienst“ hat keine eigene Kopplungszeile mehr, nur „Koppeln“ im Eintragsfeld.
            assertThat(texts(view[0].aiPanel().rows)).noneMatch(text -> text.equals("KeePass"));
            paired[0] = true; // die Fake-Anbindung koppelt beim ersten Lesen
            view[0].keePass.pair.doClick();
        });
        await(() -> view[0].keePass.status.getText().startsWith(KeePassPairingPanel.PAIRED + " ·"));
        SwingUtilities.invokeAndWait(() -> {
            assertThat(view[0].keePass.status.getText()).isEqualTo("Gekoppelt · 1 Eintrag lesbar");
            assertThat(view[0].keePass.pair.getText()).isEqualTo(KeePassPairingPanel.REPAIR);
            assertThat(view[0].keePass.unpair.isVisible()).isTrue();
            assertThat(view[0].aiPanel().pair.getText()).as("KI-Dienst folgt dem Stand")
                    .isEqualTo(AiSettingsPanel.LOAD_ENTRIES);
            view[0].keePass.unpairWithoutAsking();
            assertThat(view[0].keePass.status.getText()).isEqualTo(KeePassPairingPanel.NOT_PAIRED);
            assertThat(view[0].keePass.unpair.isVisible()).isFalse();
            assertThat(view[0].aiPanel().pair.getText()).isEqualTo(KeePassPairingPanel.PAIR);
            assertThat(view[0].keePass.feedback.text()).contains("entkoppelt").doesNotContain("sk-aus-keepass");
        });
    }

    @Test
    void storedCredentialsGetADeleteButtonInTheSecurityTab() throws Exception {
        context.vault().store("wiki", "pat", "geheimes-token".toCharArray());
        context.aiVault().store(AiSettingsPanel.KEY_ID, AiSettingsPanel.KEY_LOGIN, "sk-geheim".toCharArray());
        SettingsView[] view = new SettingsView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SettingsView(context);
            assertThat(texts(view[0].security)).contains("im Tresor")
                    .noneMatch(text -> text.contains("geheimes-token") || text.contains("sk-geheim"));
            assertThat(view[0].deleteButtons).as("ein Knopf für den API-Key, einer für die Quelle").hasSize(2);
            assertThat(view[0].deleteButtons.get(1).getToolTipText()).contains("Firmenwiki");
        });
        SwingUtilities.invokeAndWait(() -> view[0].deleteWithoutAsking(view[0].deleteButtons.get(1)));
        await(() -> !context.vault().has("wiki"));
        await(() -> view[0].securityFeedback.text().contains("Gelöscht"));
        SwingUtilities.invokeAndWait(() -> {
            assertThat(texts(view[0].security)).contains("keins nötig");
            assertThat(view[0].deleteButtons).as("nur noch der API-Key").hasSize(1);
            assertThat(context.aiVault().has(AiSettingsPanel.KEY_ID)).isTrue();
        });
    }

    /**
     * SY3 (Angelo 17.11.2026): Häkchen „Nach jedem Abgleich vorschlagen“ im Schritt „Modelle“; aus schreibt
     * {@code automatic: false}, an (Standard) nimmt den Schlüssel wieder aus der Datei; die Abschlussseite nennt es.
     */
    @Test
    void theAutomaticSynonymRunIsSwitchedOffInTheModelsStepAndBackOnAgain() throws Exception {
        SetupWizard[] wizard = new SetupWizard[1];
        SwingUtilities.invokeAndWait(() -> {
            wizard[0] = new SetupWizard(context);
            wizard[0].show(SetupWizard.MODELS);
            assertThat(wizard[0].ai.automaticSynonyms()).isTrue();
            assertThat(wizard[0].ai.changed()).isFalse();
            wizard[0].ai.automaticSynonyms.setSelected(false);
            assertThat(wizard[0].ai.changed()).as("nur das Häkchen geändert zählt als Änderung").isTrue();
            wizard[0].next.doClick();
            assertThat(wizard[0].step()).isEqualTo(SetupWizard.SUMMARY);
        });
        await(() -> !context.settings().terms().synonyms().automatic());
        assertThat(Files.readString(config, StandardCharsets.UTF_8)).contains("automatic: false");
        SwingUtilities.invokeAndWait(() -> assertThat(texts(wizard[0].summary)).containsSubsequence(
                AiSettingsPanel.AUTOMATIC_SYNONYMS_LABEL, AiSettingsPanel.AUTOMATIC_SYNONYMS_OFF));

        SwingUtilities.invokeAndWait(() -> {
            wizard[0] = new SetupWizard(context);
            wizard[0].show(SetupWizard.MODELS);
            assertThat(wizard[0].ai.automaticSynonyms()).as("liest den gespeicherten Stand").isFalse();
            wizard[0].ai.automaticSynonyms.setSelected(true);
            wizard[0].next.doClick();
        });
        await(() -> context.settings().terms().synonyms().automatic());
        assertThat(Files.readString(config, StandardCharsets.UTF_8)).doesNotContain("automatic");
        SwingUtilities.invokeAndWait(() -> assertThat(texts(wizard[0].summary)).containsSubsequence(
                AiSettingsPanel.AUTOMATIC_SYNONYMS_LABEL, AiSettingsPanel.AUTOMATIC_SYNONYMS_ON));
    }

    @Test
    void theWizardHasNoSaveButtonAndTheSummaryShowsWhatStepsThreeAndFourEntered() throws Exception {
        SetupWizard[] wizard = new SetupWizard[1];
        SwingUtilities.invokeAndWait(() -> {
            wizard[0] = new SetupWizard(context);
            assertThat(buttons(wizard[0].component())).as("kein „Speichern“ im Assistenten")
                    .noneMatch(text -> text.contains("Speichern"));
            wizard[0].show(SetupWizard.AI);
            wizard[0].ai.access.setSelectedItem(AiSettingsPanel.MANUAL);
            // Ein Port ohne Dienst: „Modelle“ lädt vergeblich, das Übernehmen hängt nicht davon ab.
            wizard[0].ai.baseUrl.setText("http://127.0.0.1:9/v1");
            wizard[0].ai.apiKey.setText("sk-wizard-geheim");
            wizard[0].next.doClick();
            assertThat(wizard[0].step()).isEqualTo(SetupWizard.MODELS);
            wizard[0].ai.visionModel.setText("vision-x");
            wizard[0].ai.rerankModel.setText("rerank-y");
            wizard[0].ai.chatModel.setText("chat-z");
            assertThat(wizard[0].ai.judgedPairs()).as("Standard: Chat-Prüfung aus").isZero();
            assertThat(wizard[0].ai.automaticSynonyms()).as("Standard: Lauf nach jedem Abgleich an").isTrue();
            wizard[0].ai.judgedPairs.setValue(30);
            assertThat(wizard[0].ai.changed()).as("nur das Budget geändert zählt als Änderung").isTrue();
            assertThat(wizard[0].ai.simplification()).as("Vorgabe der Vorstufe").isEqualTo(
                    com.aresstack.spaceharvester.application.configuration.SimplificationSettings.DEFAULT);
            wizard[0].ai.simplifyMode.setSelectedItem(AiSettingsPanel.SIMPLIFY_ALWAYS);
            wizard[0].ai.simplifyParallel.setValue(3);
            wizard[0].ai.simplifyModel.setText("einfach-m");
            assertThat(wizard[0].ai.check()).as("Vorgabe des Prüfschritts, Modellfeld leer").isEqualTo(
                    com.aresstack.spaceharvester.application.configuration.CheckSettings.DEFAULT);
            wizard[0].ai.checkScope.setSelectedItem(AiSettingsPanel.CHECK_ALL);
            wizard[0].ai.checkParallel.setValue(5);
            wizard[0].ai.checkModel.setText("pruef-m");
            wizard[0].next.doClick();
            assertThat(wizard[0].step()).isEqualTo(SetupWizard.SUMMARY);
        });
        long until = System.currentTimeMillis() + 120_000;
        java.util.List<String> summary = new java.util.ArrayList<>();
        while (System.currentTimeMillis() < until) {
            SwingUtilities.invokeAndWait(() -> {
                summary.clear();
                summary.addAll(texts(wizard[0].summary));
            });
            if (summary.contains("rerank-y") && summary.contains("im Tresor")) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(summary).as("ohne „Speichern“ übernommen").contains("vision-x", "rerank-y", "chat-z",
                AiSettingsPanel.JUDGED_PAIRS_LABEL, "30 " + AiSettingsPanel.JUDGED_PAIRS_UNIT, "im Tresor",
                AiSettingsPanel.AUTOMATIC_SYNONYMS_LABEL, AiSettingsPanel.AUTOMATIC_SYNONYMS_ON,
                AiSettingsPanel.SIMPLIFY_LABEL, "immer · 3 Abschnitte zugleich · Modell einfach-m",
                AiSettingsPanel.CHECK_SCOPE_LABEL, "alle · 5 Sätze zugleich · Modell pruef-m")
                .doesNotContain("fehlt");
        assertThat(String.join("\n", summary)).doesNotContain("sk-wizard-geheim");
        assertThat(Files.readString(config, StandardCharsets.UTF_8)).contains("vision-x", "rerank-y",
                "http://127.0.0.1:9/v1", "max-judged-pairs: 30", "mode: always", "parallel: 3", "scope: all",
                "parallel: 5", "pruef-m",
                "model: \"einfach-m\"").doesNotContain("sk-wizard-geheim")
                .as("Häkchen an ist der Standard und steht nicht in der Datei").doesNotContain("automatic");
        SwingUtilities.invokeAndWait(() -> assertThat(wizard[0].ai.modelRetry.active())
                .as("„Modelle“ verlassen: keine Wiederholung").isFalse());
    }

    @Test
    void closingLikeLaterSavesFirstAndIncompleteEntriesKeepTheWizardOpen() throws Exception {
        SetupWizard[] wizard = new SetupWizard[1];
        int[] closed = {0};
        SwingUtilities.invokeAndWait(() -> {
            wizard[0] = new SetupWizard(context);
            wizard[0].close = () -> closed[0]++;
            wizard[0].show(SetupWizard.AI);
            wizard[0].ai.access.setSelectedItem(AiSettingsPanel.MANUAL);
            wizard[0].ai.baseUrl.setText("http://127.0.0.1:9/v1");
            wizard[0].ai.dimensions.setText("keine Zahl");
            // Escape und ✕ rufen dasselbe wie „Später“.
            wizard[0].requestClose();
            assertThat(closed[0]).as("unvollständig: bleibt offen").isZero();
            assertThat(wizard[0].feedback.text()).contains("nicht übernommen", "Dimension");
            wizard[0].ai.dimensions.setText("");
            wizard[0].ai.visionModel.setText("vision-beim-schliessen");
            wizard[0].requestClose();
        });
        long until = System.currentTimeMillis() + 120_000;
        int[] seen = {0};
        while (seen[0] == 0 && System.currentTimeMillis() < until) {
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> seen[0] = closed[0]);
        }
        assertThat(seen[0]).as("erst übernommen, dann geschlossen").isEqualTo(1);
        assertThat(Files.readString(config, StandardCharsets.UTF_8)).contains("vision-beim-schliessen");
    }

    @Test
    void advancedHoldsDimensionAndChunkingAndInputsShareOneEdge() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AiSettingsPanel panel = new AiSettingsPanel(context);
            javax.swing.JComponent models = panel.modelsComponent();
            // Hoch genug für alle Zeilen, auch wenn neue dazukommen: Reicht die Höhe nicht einmal für die
            // Mindestgrößen, schrumpft das Raster Zeilen auf null und nichts steht mehr bündig.
            models.setSize(700, 1800);
            layout(models);
            assertThat(panel.dimensions.isVisible()).as("Dimension unter „Erweitert“").isFalse();
            assertThat(panel.simplifyParallel.getParent().isVisible()).as("Abschnitte zugleich unter „Erweitert“").isFalse();
            assertThat(panel.checkParallel.getParent().isVisible()).as("Sätze zugleich unter „Erweitert“").isFalse();
            assertThat(panel.chunkTokens.isVisible()).isFalse();
            panel.advanced.doClick();
            models.setSize(700, Math.max(1800, models.getMinimumSize().height));
            layout(models);
            assertThat(panel.dimensions.isVisible()).isTrue();
            // Modellfelder tragen ▾ im Feld (InputFrame): verglichen wird die äußere Kante des Felds.
            java.awt.Component chatFrame = panel.chatModel.getParent();
            int left = javax.swing.SwingUtilities.convertPoint(chatFrame, 0, 0, models).x;
            for (java.awt.Component field : java.util.List.of(panel.embeddingModel.getParent(),
                    panel.visionModel.getParent(), panel.rerankModel.getParent(),
                    panel.simplifyModel.getParent(), panel.checkModel.getParent(), panel.dimensions,
                    panel.chunkTokens)) {
                assertThat(javax.swing.SwingUtilities.convertPoint(field, 0, 0, models).x).isEqualTo(left);
            }
            // Rechts neben dem Text-Modell stehen Tokenlänge und ?-Knopf (Angelo 21.11.2026): verglichen wird die
            // rechte Kante der ganzen Zeile.
            java.awt.Component chatRow = chatFrame.getParent();
            int right = javax.swing.SwingUtilities.convertPoint(chatRow, chatRow.getWidth(), 0, models).x;
            assertThat(SwingUtilities.isDescendingFrom(panel.chatMaxTokens, chatRow)).isTrue();
            assertThat(SwingUtilities.isDescendingFrom(panel.imageMaxTokens, panel.imageModel.getParent().getParent()))
                    .as("Bildgenerierung: Modell und Tokenlänge in einer Zeile").isTrue();
            // Nachdenken je Modell ganz rechts in derselben Zeile (Angelo 22.11.2026).
            assertThat(SwingUtilities.isDescendingFrom(panel.stepThinking, chatRow)).isTrue();
            assertThat(SwingUtilities.isDescendingFrom(panel.visionThinking, panel.visionModel.getParent().getParent()))
                    .isTrue();
            assertThat(SwingUtilities.isDescendingFrom(panel.imageThinking, panel.imageModel.getParent().getParent()))
                    .isTrue();
            assertThat(SwingUtilities.isDescendingFrom(panel.checkThinking, panel.checkModel.getParent().getParent()))
                    .isTrue();
            // Eigene Zeilen für Zusammenfassung, ihren Plan und den Podcast (Angelo 22.11.2026).
            for (java.util.List<java.awt.Component> row : java.util.List.of(
                    java.util.List.<java.awt.Component>of(panel.summaryModel, panel.summaryMaxTokens,
                            panel.summaryThinking),
                    java.util.List.<java.awt.Component>of(panel.summaryPlanModel, panel.summaryPlanMaxTokens,
                            panel.summaryPlanThinking),
                    java.util.List.<java.awt.Component>of(panel.podcastModel, panel.podcastMaxTokens,
                            panel.podcastThinking),
                    java.util.List.<java.awt.Component>of(panel.imagePlanModel, panel.imagePlanMaxTokens,
                            panel.imagePlanThinking),
                    java.util.List.<java.awt.Component>of(panel.deadlineModel, panel.deadlineMaxTokens,
                            panel.deadlineThinking))) {
                java.awt.Container line = row.get(0).getParent().getParent();
                assertThat(SwingUtilities.isDescendingFrom(row.get(1), line)).isTrue();
                assertThat(SwingUtilities.isDescendingFrom(row.get(2), line)).isTrue();
                assertThat(javax.swing.SwingUtilities.convertPoint(row.get(0).getParent(), 0, 0, models).x)
                        .isEqualTo(left);
            }
            assertThat(javax.swing.SwingUtilities.convertPoint(panel.dimensions, panel.dimensions.getWidth(), 0,
                    models).x).as("gleiche rechte Kante wie Feld mit ▾").isEqualTo(right);
            assertThat(javax.swing.SwingUtilities.convertPoint(panel.advanced, 0, 0, models).x)
                    .as("„Erweitert“ linksbündig").isLessThan(left);
        });
    }

    private static void layout(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container nested) {
                layout(nested);
            }
        }
    }

    private static java.util.List<String> buttons(java.awt.Container container) {
        java.util.List<String> texts = new java.util.ArrayList<>();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof javax.swing.AbstractButton button && button.isVisible()) {
                texts.add(button.getText());
            }
            if (child instanceof java.awt.Container nested && child.isVisible()) {
                texts.addAll(buttons(nested));
            }
        }
        return texts;
    }

    private static java.util.List<String> tooltips(java.awt.Container container) {
        java.util.List<String> tips = new java.util.ArrayList<>();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof javax.swing.JComponent component) {
                tips.add(component.getToolTipText());
                tips.addAll(tooltips(component));
            }
        }
        return tips;
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        SettingDialogsTest.await(condition);
    }

    private static java.util.List<String> texts(java.awt.Container container) {
        java.util.List<String> texts = new java.util.ArrayList<>();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof javax.swing.JLabel label) {
                texts.add(label.getText());
            } else if (child instanceof javax.swing.text.JTextComponent area) {
                texts.add(area.getText());
            } else if (child instanceof java.awt.Container nested) {
                texts.addAll(texts(nested));
            }
        }
        return texts;
    }
}
