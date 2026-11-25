package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.editor.EditorSession;
import com.aresstack.spaceharvester.deck.ftp.ConnectionState;
import com.aresstack.spaceharvester.deck.ftp.DatasetAttributes;
import com.aresstack.spaceharvester.deck.ftp.EntryList;
import com.aresstack.spaceharvester.deck.ftp.FtpProfile;
import com.aresstack.spaceharvester.deck.ftp.TransferOptions;
import com.aresstack.spaceharvester.deck.ftp.jes.SpoolNaming;
import com.aresstack.spaceharvester.deck.ftp.jes.SpoolText;
import com.aresstack.spaceharvester.deck.ftp.FakeMvsFtpHost;
import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import com.aresstack.spaceharvester.deck.ftp.RemoteEntry;
import com.aresstack.spaceharvester.deck.ftp.jes.JesJob;
import com.aresstack.spaceharvester.deck.ftp.jes.SpoolFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der FTP-Bereich des Command Deck (#1142): Datasets und JES-Jobs gegen den Schein-z/OS-Host. */
class FtpWorkspaceTest {

    private CommandDeck deck;

    @AfterEach
    void shutdown() {
        if (deck != null) {
            deck.shutdown();
        }
        com.aresstack.spaceharvester.app.ui.AppState.shared().put(FtpConnectForm.CONFIGURED_KEY, (String) null);
    }

    /** KeePass mit einem Eintrag für den Schein-Host. */
    static ServerAccounts keePass(FakeMvsFtpHost host) {
        ServerAccount account = new ServerAccount("Raumhafen", host.host(), "KKR");
        return new ServerAccounts() {
            @Override
            public List<ServerAccount> accounts() {
                return List.of(account);
            }

            @Override
            public ServerCredentials credentials(String entry) {
                return new ServerCredentials(account, "geheim".toCharArray());
            }
        };
    }

    @Test
    void theSwitchStandsTopRightOnlyInTheFtpAreaAndNothingConnectsByItself() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            deck = new CommandDeck();
            assertThat(deck.perspective().tools()).isSameAs(deck.tools());
            assertThat(deck.tools().getComponentCount()).isZero();
            deck.show(DeckArea.FTP);
            assertThat(deck.tools().getComponent(0)).isSameAs(deck.ftp().tools());
            assertThat(deck.ftp().toggle().selected()).isIn(FtpSessionView.DATASETS, FtpSessionView.JOBS);
            assertThat(deck.ftpSessions().all()).as("Öffnen baut keine Verbindung auf").isEmpty();
            deck.ftp().form().submit();
            assertThat(deck.ftp().form().problemText()).contains("Server wählen");
            assertThat(deck.ftpSessions().all()).isEmpty();
            deck.show(DeckArea.TERMINAL);
            assertThat(deck.tools().getComponentCount()).isZero();
        });
    }

    @Test
    void theAdvancedSettingsOfTheConnectionGoIntoTheProfile() throws Exception {
        ServerAccount account = new ServerAccount("Raumhafen", "sternwarte.example", "KKR");
        ServerAccounts keePass = new ServerAccounts() {
            @Override
            public List<ServerAccount> accounts() {
                return List.of(account);
            }

            @Override
            public ServerCredentials credentials(String entry) {
                return new ServerCredentials(account, "geheim".toCharArray());
            }
        };
        SwingUtilities.invokeAndWait(() -> {
            FtpConnectForm form = new FtpConnectForm(keePass, (profile, entry) -> { });
            form.show(List.of(account));
            form.server.setSelectedIndex(1);
            form.timeout.setText("15");
            form.controlTimeout.setText("");
            form.dataTimeout.setText("90");
            form.startHlq.setText("orbit");
            form.syslib.setText("orbit.copylib, 'ORBIT.CPY'");
            form.fileType.setSelectedItem(TransferOptions.FileType.EBCDIC);
            form.textFormat.setSelectedItem(TransferOptions.TextFormat.CARRIAGE_CONTROL);
            form.structure.setSelectedItem(TransferOptions.Structure.FILE);
            form.attempts.setText("4");
            form.backoff.setText("200");
            form.exponential.setSelected(true);
            form.maxBackoff.setText("1000");
            form.codes.setText("421, 426");
            form.spoolMode.setSelectedItem(SpoolNaming.Mode.PROBE);
            form.spoolConnections.setText("3");
            FtpProfile profile = form.profile();
            assertThat(profile.host()).isEqualTo("sternwarte.example");
            assertThat(profile.connectTimeoutSeconds()).isEqualTo(15);
            assertThat(profile.controlTimeoutSeconds()).as("leer = wie das Zeitlimit").isEqualTo(15);
            assertThat(profile.dataTimeoutSeconds()).isEqualTo(90);
            assertThat(profile.startHlq()).isEqualTo("ORBIT");
            assertThat(profile.syslib()).containsExactly("ORBIT.COPYLIB", "ORBIT.CPY");
            assertThat(profile.withUseProxy(false).syslib()).isEqualTo(profile.syslib());
            assertThat(profile.transfer()).isEqualTo(new TransferOptions(TransferOptions.FileType.EBCDIC,
                    TransferOptions.TextFormat.CARRIAGE_CONTROL, TransferOptions.Structure.FILE,
                    TransferOptions.TransferMode.STREAM));
            assertThat(profile.retry().maxAttempts()).isEqualTo(4);
            assertThat(profile.retry().backoffMillis()).isEqualTo(200);
            assertThat(profile.retry().exponential()).isTrue();
            assertThat(profile.retry().maxBackoffMillis()).isEqualTo(1000);
            assertThat(profile.retry().replyCodes()).containsExactlyInAnyOrder(421, 426);
            assertThat(profile.spoolNaming().mode()).isEqualTo(SpoolNaming.Mode.PROBE);
            assertThat(profile.spoolNaming().connections()).isEqualTo(3);
            assertThat(profile.useProxy()).as("Vorgabe: Proxy wie bisher").isTrue();
            form.useProxy.setSelected(false);
            assertThat(form.profile().useProxy()).isFalse();
            form.attempts.setText("viele");
            form.submit();
            assertThat(form.problemText()).contains("Versuche");
        });
    }

    @Test
    void theDeleteQuestionNamesAtMostEightEntries() {
        List<RemoteEntry> many = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> new RemoteEntry("M" + i, "'KKR.JCL(M" + i + ")'", false, 0, java.util.Optional.empty())).toList();
        String question = FtpDatasetsView.deleteQuestion(many);
        assertThat(question).startsWith("11 Einträge wirklich löschen?").contains("M7").doesNotContain("M8")
                .contains("… und 3 weitere");
        assertThat(FtpDatasetsView.deleteQuestion(many.subList(0, 1))).isEqualTo("„M0“ wirklich löschen?");
    }

    @Test
    void browsesDatasetsReadsMembersAndWorksWithJobs(@TempDir Path temp) throws Exception {
        try (FakeMvsFtpHost host = FakeMvsFtpHost.sample()) {
            ServerAccounts keePass = keePass(host);
            List<ServerAccount> entries = keePass.accounts();
            SwingUtilities.invokeAndWait(() -> {
                deck = new CommandDeck(keePass);
                deck.show(DeckArea.FTP);
                deck.ftp().toggle().select(FtpSessionView.DATASETS);
                FtpConnectForm form = deck.ftp().form();
                form.show(entries);
                form.server.setSelectedIndex(1);
                form.port.setText(String.valueOf(host.port()));
                form.timeout.setText("10");
                form.submit();
            });
            FtpSession session = deck.ftpSessions().all().get(0);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            waitFor(() -> session.state() == ConnectionState.CONNECTED, until);
            FtpSessionView view = onEdt(() -> deck.ftp().view(session));
            FtpDatasetsView datasets = view.datasets();
            waitFor(() -> onEdt(() -> datasets.shown().size() == 3), until);
            assertThat(onEdt(datasets::current)).isEqualTo("'KKR'");
            assertThat(onEdt(() -> datasets.shown().stream().map(RemoteEntry::name).toList()))
                    .containsExactly("DATA", "JCL", "SRC");

            // Wie MainframeMate: sortieren, Detail-Spalten, Filter mit roter Warnung.
            SwingUtilities.invokeAndWait(() -> datasets.sort(EntryList.Sort.NAME_DESC));
            assertThat(onEdt(() -> datasets.shown().stream().map(RemoteEntry::name).toList()))
                    .containsExactly("SRC", "JCL", "DATA");
            SwingUtilities.invokeAndWait(() -> datasets.sort(EntryList.Sort.NAME_ASC));
            assertThat(onEdt(datasets::columns)).containsExactly("Name", "Art");
            SwingUtilities.invokeAndWait(datasets::toggleDetails);
            waitFor(() -> onEdt(() -> datasets.columns().contains("Format") && datasets.shown().size() == 3), until);
            assertThat(onEdt(() -> datasets.shown().get(1).details().format())).isEqualTo("FB/80");
            SwingUtilities.invokeAndWait(datasets::toggleDetails);
            waitFor(() -> onEdt(() -> !datasets.columns().contains("Format") && datasets.shown().size() == 3), until);
            SwingUtilities.invokeAndWait(() -> datasets.filterFor("JC"));
            assertThat(onEdt(() -> datasets.shown().stream().map(RemoteEntry::name).toList())).containsExactly("JCL");
            SwingUtilities.invokeAndWait(() -> datasets.filterFor("XYZ"));
            assertThat(onEdt(datasets::filterAlarming)).isTrue();
            SwingUtilities.invokeAndWait(() -> datasets.filterFor(""));
            assertThat(onEdt(datasets::filterAlarming)).isFalse();

            // Ein Member unter lauter Datasets: erst die Frage „Kein PDS“, dann das neue PDS mit Anlageparametern.
            List<String> titles = new java.util.concurrent.CopyOnWriteArrayList<>();
            List<String> contexts = new java.util.concurrent.CopyOnWriteArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                datasets.confirm = (title, question) -> {
                    titles.add(title);
                    return true;
                };
                datasets.datasetDialog = context -> {
                    contexts.add(context);
                    return new FtpDatasetsView.NewDataset("NEU", DatasetAttributes.DEFAULT);
                };
                datasets.askNewMember();
            });
            waitFor(() -> host.datasetExists("KKR.NEU"), until);
            assertThat(titles).containsExactly("Kein PDS");
            assertThat(contexts).containsExactly("KKR");
            assertThat(host.commands()).contains("SITE " + DatasetAttributes.DEFAULT.siteCommand());
            waitFor(() -> onEdt(() -> datasets.shown().size() == 4), until);

            // In das PDS wechseln und ein Member lesen.
            SwingUtilities.invokeAndWait(() -> datasets.openRow(1));
            waitFor(() -> onEdt(() -> datasets.current().equals("'KKR.JCL'") && datasets.shown().size() == 2), until);
            SwingUtilities.invokeAndWait(() -> datasets.openRow(0));
            waitFor(() -> onEdt(() -> datasets.documentText().startsWith("//BUILD")), until);
            assertThat(onEdt(view::statusText)).contains("'KKR.JCL(BUILD)'");

            // Im gemeinsamen Editor bearbeiten und zurückschreiben.
            EditorSession build = onEdt(datasets::document);
            assertThat(build.name()).isEqualTo("BUILD");
            assertThat(deck.editors().all()).contains(build);
            SwingUtilities.invokeAndWait(() -> datasets.editor(build).type("*NEU\n"));
            waitFor(() -> onEdt(build::dirty), until);
            SwingUtilities.invokeAndWait(build::save);
            waitFor(() -> !build.dirty() && build.state() == EditorSession.State.READY, until);
            assertThat(host.text("KKR.JCL(BUILD)")).contains("*NEU").contains("//BUILD    JOB");
            // Ändert jemand das Member auf dem Host, speichert der Editor nicht blind darüber.
            host.member("KKR.JCL", "BUILD", "//BUILD    JOB VON AUSSEN");
            SwingUtilities.invokeAndWait(() -> datasets.editor(build).type("*NOCH EINS\n"));
            waitFor(() -> onEdt(build::dirty), until);
            SwingUtilities.invokeAndWait(build::save);
            waitFor(() -> build.state() == EditorSession.State.CONFLICT, until);
            assertThat(host.text("KKR.JCL(BUILD)")).isEqualTo("//BUILD    JOB VON AUSSEN");

            // Hochladen: der Dateiname wird zum Member.
            Path local = temp.resolve("orbit.jcl");
            Files.writeString(local, "//ORBIT    JOB\n", StandardCharsets.UTF_8);
            SwingUtilities.invokeAndWait(() -> datasets.uploadFile(local));
            waitFor(() -> host.text("KKR.JCL(ORBIT)") != null, until);
            assertThat(host.text("KKR.JCL(ORBIT)")).isEqualTo("//ORBIT    JOB");

            // Ein vorhandenes Member wird nie still überschrieben: erst die Rückfrage, bei Nein bleibt es.
            waitFor(() -> onEdt(() -> datasets.shown().stream().anyMatch(e -> e.name().equals("ORBIT"))), until);
            List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                datasets.confirmOverwrite = question -> {
                    asked.add(question);
                    return false;
                };
                datasets.createNamed("ORBIT");
                datasets.uploadFile(local);
            });
            assertThat(asked).hasSize(2).allMatch(question -> question.contains("ORBIT") && question.toLowerCase(java.util.Locale.ROOT).contains("wirklich"));
            assertThat(onEdt(view::statusText)).contains("Nicht überschrieben");
            assertThat(host.text("KKR.JCL(ORBIT)")).isEqualTo("//ORBIT    JOB");
            // Ein Zeichen, das der Zeichensatz nicht kennt, wird gemeldet statt still zu „?“.
            Path euro = temp.resolve("preis.jcl");
            Files.writeString(euro, "//PREIS    JOB\n* 5 €\n", StandardCharsets.UTF_8);
            SwingUtilities.invokeAndWait(() -> datasets.uploadFile(euro));
            waitFor(() -> onEdt(view::statusText).contains("€"), until);
            assertThat(onEdt(view::statusText)).contains("ISO-8859-1");
            // Der Stand steht in der Statuszeile der App (nicht in der Aktionszeile), Fehler rot.
            assertThat(onEdt(() -> deck.ftp().statusNote().text())).contains("€");
            assertThat(onEdt(() -> deck.ftp().statusNote().tone()))
                    .isEqualTo(com.aresstack.spaceharvester.app.ui.controls.StatusNote.Tone.ERROR);
            assertThat(host.text("KKR.JCL(PREIS)")).isNull();

            // Mehrere auf einmal löschen.
            List<RemoteEntry> doomed = onEdt(() -> datasets.shown().stream()
                    .filter(e -> e.name().equals("ORBIT") || e.name().equals("STERNE")).toList());
            assertThat(doomed).hasSize(2);
            SwingUtilities.invokeAndWait(() -> datasets.deleteEntries(doomed));
            waitFor(() -> host.text("KKR.JCL(ORBIT)") == null && host.text("KKR.JCL(STERNE)") == null, until);
            waitFor(() -> onEdt(view::statusText).contains("2 gelöscht"), until);

            // Umschalten auf JES-Jobs tauscht nur die Mitte; die Verbindung bleibt.
            SwingUtilities.invokeAndWait(() -> deck.ftp().toggle().select(FtpSessionView.JOBS));
            FtpJobsView jobs = view.jobs();
            waitFor(() -> onEdt(() -> jobs.shownJobs().size() == 3), until);
            assertThat(onEdt(view::view)).isEqualTo(FtpSessionView.JOBS);
            assertThat(onEdt(() -> jobs.shownJobs().stream().map(JesJob::jobName).toList()))
                    .containsExactly("BUILD", "STERNE", "KOMET");
            SwingUtilities.invokeAndWait(() -> jobs.table().getSelectionModel().setSelectionInterval(0, 0));
            waitFor(() -> onEdt(() -> jobs.shownSpool().size() == 3), until);
            assertThat(onEdt(() -> jobs.shownSpool().stream().map(SpoolFile::ddName).toList()))
                    .containsExactly("JESMSGLG", "JESJCL", "SYSPRINT");
            waitFor(() -> onEdt(() -> jobs.spoolTextValue().contains("$HASP373")), until);
            assertThat(onEdt(jobs::infoText)).contains("Job-ID: ").contains("Name: BUILD").contains("Besitzer: ");
            SwingUtilities.invokeAndWait(() -> jobs.spoolTable().getSelectionModel().setSelectionInterval(2, 2));
            waitFor(() -> onEdt(() -> jobs.spoolTextValue().contains("IDC0001I")), until);

            // Sprache wählbar.
            SwingUtilities.invokeAndWait(() -> jobs.languageBox().setSelectedItem(SpoolText.Language.PLAIN));
            assertThat(onEdt(jobs::languageShown)).isEqualTo(SpoolText.Language.PLAIN);
            SwingUtilities.invokeAndWait(() -> jobs.languageBox().setSelectedItem(SpoolText.Language.AUTO));

            // „Diagramm“ wie MainframeMate JobDetailTab: ohne erkennbare Struktur (hier JESJCL ohne JES-Spaltenpräfix)
            // bleibt der Text stehen, und die Statuszeile sagt, warum.
            SwingUtilities.invokeAndWait(() -> jobs.spoolTable().getSelectionModel().setSelectionInterval(1, 1));
            waitFor(() -> onEdt(() -> jobs.spoolTextValue().contains("STEP1")), until);
            SwingUtilities.invokeAndWait(() -> jobs.diagram().toggle());
            waitFor(() -> onEdt(view::statusText).contains("Kein Diagramm erzeugbar"), until);
            assertThat(onEdt(() -> jobs.diagram().active())).isFalse();

            // „Gesamter Output“ zeigt alle Spool-Dateien zusammen.
            SwingUtilities.invokeAndWait(jobs::showWholeOutput);
            waitFor(() -> onEdt(() -> jobs.spoolTextValue().contains("$HASP373")
                    && jobs.spoolTextValue().contains("IDC0001I")), until);

            // Lokale Suche (Regex) und lokaler Jobname-Filter.
            SwingUtilities.invokeAndWait(() -> jobs.searchFor("STER"));
            assertThat(onEdt(() -> jobs.shownJobs().stream().map(JesJob::jobName).toList())).containsExactly("STERNE");
            SwingUtilities.invokeAndWait(() -> jobs.searchFor("["));
            assertThat(onEdt(jobs::searchAlarming)).isTrue();
            SwingUtilities.invokeAndWait(() -> jobs.searchFor(""));
            assertThat(onEdt(jobs::searchAlarming)).isFalse();
            SwingUtilities.invokeAndWait(() -> jobs.nameFilter().toggle("KOMET", true));
            assertThat(onEdt(() -> jobs.shownJobs().stream().map(JesJob::jobName).toList())).containsExactly("KOMET");
            SwingUtilities.invokeAndWait(() -> jobs.nameFilter().enabled(false));
            assertThat(onEdt(jobs::shownJobs)).hasSize(3);
            SwingUtilities.invokeAndWait(() -> {
                jobs.nameFilter().clear();
                jobs.nameFilter().enabled(true);
            });
            assertThat(onEdt(jobs::shownJobs)).hasSize(3);

            // Einreichen aus einer lokalen Datei; die Liste wird danach neu abgefragt.
            SwingUtilities.invokeAndWait(() -> jobs.submit(local));
            waitFor(() -> onEdt(() -> jobs.shownJobs().size() == 4), until);
            assertThat(host.submitted()).containsExactly("//ORBIT    JOB\n");
            assertThat(onEdt(view::statusText)).contains("JOB04711").contains("ORBIT");

            // „▶ Ausführen“ im Editor reicht den Editor-Inhalt (JCL) ein, wie in MainframeMate.
            SwingUtilities.invokeAndWait(() -> datasets.runJcl(datasets.editor(build)));
            waitFor(() -> host.submitted().size() == 2 || onEdt(view::statusText).contains("nur mit JCL"), until);
            assertThat(host.submitted()).hasSize(2);
            assertThat(host.submitted().get(1)).contains("*NOCH EINS").contains("//BUILD    JOB");
            // Ohne Fenster ist nichts „sichtbar“; die Oberfläche fragt sonst beim Zeigen neu ab.
            SwingUtilities.invokeAndWait(jobs::refresh);
            waitFor(() -> onEdt(() -> jobs.shownJobs().size() == 5), until);

            // Löschen (die Rückfrage gehört der Oberfläche; hier direkt).
            JesJob submitted = onEdt(() -> jobs.shownJobs().get(3));
            SwingUtilities.invokeAndWait(() -> jobs.deleteJob(submitted));
            waitFor(() -> onEdt(() -> jobs.shownJobs().size() == 4), until);

            // Bereichswechsel trennt nichts.
            SwingUtilities.invokeAndWait(() -> {
                deck.show(DeckArea.TERMINAL);
                deck.show(DeckArea.FTP);
            });
            assertThat(session.state()).isEqualTo(ConnectionState.CONNECTED);
            assertThat(host.logins()).as("eine Anmeldung je Verbindung (Datasets, JES)").isEqualTo(2);
        }
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> work) throws Exception {
        Object[] result = new Object[1];
        Exception[] error = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = work.call();
            } catch (Exception e) {
                error[0] = e;
            }
        });
        if (error[0] != null) {
            throw error[0];
        }
        @SuppressWarnings("unchecked")
        T typed = (T) result[0];
        return typed;
    }

    @FunctionalInterface
    private interface Check {
        boolean ok() throws Exception;
    }

    private static void waitFor(Check condition, long until) throws Exception {
        while (!condition.ok()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("Zeit abgelaufen");
            }
            Thread.sleep(30);
        }
    }
}
