package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.editor.EditorSession;
import com.aresstack.spaceharvester.deck.ftp.ConnectionState;
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
            SwingUtilities.invokeAndWait(() -> jobs.spoolList().setSelectedIndex(2));
            waitFor(() -> onEdt(() -> jobs.spoolTextValue().contains("IDC0001I")), until);

            // Einreichen aus einer lokalen Datei; die Liste wird danach neu abgefragt.
            SwingUtilities.invokeAndWait(() -> jobs.submit(local));
            waitFor(() -> onEdt(() -> jobs.shownJobs().size() == 4), until);
            assertThat(host.submitted()).containsExactly("//ORBIT    JOB\n");
            assertThat(onEdt(view::statusText)).contains("JOB04711").contains("ORBIT");

            // Löschen (die Rückfrage gehört der Oberfläche; hier direkt).
            JesJob submitted = onEdt(() -> jobs.shownJobs().get(3));
            SwingUtilities.invokeAndWait(() -> jobs.deleteJob(submitted));
            waitFor(() -> onEdt(() -> jobs.shownJobs().size() == 3), until);

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
