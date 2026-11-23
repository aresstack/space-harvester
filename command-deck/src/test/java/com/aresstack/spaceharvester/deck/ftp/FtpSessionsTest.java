package com.aresstack.spaceharvester.deck.ftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.ftp.commonsnet.CommonsNetFtpConnector;
import com.aresstack.spaceharvester.deck.ftp.jes.JesJob;
import com.aresstack.spaceharvester.deck.ftp.jes.JobFilter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FtpSessionsTest {

    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
    private FakeMvsFtpHost host;
    private FtpSessions sessions;

    @BeforeEach
    void start() throws Exception {
        host = FakeMvsFtpHost.sample();
        sessions = new FtpSessions(new CommonsNetFtpConnector(), background);
    }

    @AfterEach
    void stop() throws Exception {
        sessions.closeAll();
        background.shutdownNow();
        host.close();
    }

    private FtpSession.CredentialSource login(String password) {
        return () -> new ServerCredentials(new ServerAccount("Raumhafen", host.host(), "KKR"), password.toCharArray());
    }

    @Test
    void opensConnectsAndReportsTheState() throws Exception {
        List<ConnectionState> seen = new CopyOnWriteArrayList<>();
        sessions.onOpened(opened -> opened.observe(changed -> seen.add(changed.state())));
        FtpSession session = sessions.open(host.profile(), "Raumhafen", login("geheim"));
        assertThat(session.connect().get(10, TimeUnit.SECONDS)).isEqualTo("'KKR'");
        assertThat(session.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(session.mvs()).isTrue();
        assertThat(session.user()).isEqualTo("KKR");
        assertThat(session.label()).isEqualTo("Raumhafen");
        assertThat(seen).contains(ConnectionState.CONNECTED);
        assertThat(sessions.all()).containsExactly(session);
        // Beide Aufträge über dieselbe Verbindung: nur eine Anmeldung.
        assertThat(host.logins()).isEqualTo(1);
    }

    @Test
    void aWrongPasswordFailsWithAReason() throws Exception {
        FtpSession session = sessions.open(host.profile(), "Raumhafen", login("falsch"));
        assertThatThrownBy(() -> session.connect().get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThat(session.state()).isEqualTo(ConnectionState.FAILED);
        assertThat(session.failure()).hasValueSatisfying(reason -> assertThat(reason).contains("Anmeldung"));
    }

    @Test
    void readsReconnectAfterTheHostDroppedTheConnection() throws Exception {
        FtpSession session = sessions.open(host.profile(), "Raumhafen", login("geheim"));
        session.connect().get(10, TimeUnit.SECONDS);
        host.dropConnections();
        List<RemoteEntry> members = session.files(files -> files.list("'KKR.JCL'")).get(10, TimeUnit.SECONDS);
        assertThat(members).extracting(RemoteEntry::name).containsExactly("BUILD", "STERNE");
        assertThat(host.logins()).isEqualTo(2);
        assertThat(session.state()).isEqualTo(ConnectionState.CONNECTED);
    }

    @Test
    void writesAreNeverRepeated() throws Exception {
        FtpSession session = sessions.open(host.profile(), "Raumhafen", login("geheim"));
        session.connect().get(10, TimeUnit.SECONDS);
        host.dropConnections();
        assertThatThrownBy(() -> session.filesOnce(files -> {
            files.write("'KKR.JCL(EINMAL)'", "x");
            return null;
        }).get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        // Der nächste Auftrag verbindet neu.
        session.filesOnce(files -> {
            files.write("'KKR.JCL(EINMAL)'", "x");
            return null;
        }).get(10, TimeUnit.SECONDS);
        assertThat(host.text("KKR.JCL(EINMAL)")).isEqualTo("x");
    }

    @Test
    void jobsUseTheirOwnConnectionInJesMode() throws Exception {
        FtpSession session = sessions.open(host.profile(), "Raumhafen", login("geheim"));
        session.connect().get(10, TimeUnit.SECONDS);
        List<JesJob> jobs = session.jobs(jes -> jes.jobs(JobFilter.mine())).get(10, TimeUnit.SECONDS);
        assertThat(jobs).hasSize(3);
        assertThat(host.logins()).isEqualTo(2);
        // Die Dataset-Verbindung bleibt im Dataset-Modus.
        assertThat(session.files(files -> files.list("'KKR.JCL'")).get(10, TimeUnit.SECONDS)).hasSize(2);
    }

    @Test
    void theCredentialsAreClosedAfterLogin() throws Exception {
        List<ServerCredentials> handedOut = new CopyOnWriteArrayList<>();
        FtpSession session = sessions.open(host.profile(), "Raumhafen", () -> {
            ServerCredentials credentials = new ServerCredentials(new ServerAccount("Raumhafen", host.host(), "KKR"),
                    "geheim".toCharArray());
            handedOut.add(credentials);
            return credentials;
        });
        session.connect().get(10, TimeUnit.SECONDS);
        assertThat(handedOut).hasSize(1);
        assertThat(handedOut.get(0).copyPassword()).containsOnly('\0');
    }

    @Test
    void afterDisconnectNothingIsAccepted() throws Exception {
        FtpSession session = sessions.open(host.profile(), "Raumhafen", login("geheim"));
        session.connect().get(10, TimeUnit.SECONDS);
        sessions.close(session);
        assertThat(session.state()).isEqualTo(ConnectionState.DISCONNECTED);
        assertThat(sessions.all()).isEmpty();
        assertThatThrownBy(() -> session.files(FileConnection::home).get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsFollowTheRetryPolicyOfTheProfile() throws Exception {
        FtpProfile once = host.profile().withRetry(new RetryPolicy(1, 0, false, 0, true, true, java.util.Set.of()));
        FtpSession session = sessions.open(once, "Raumhafen", login("geheim"));
        session.connect().get(10, TimeUnit.SECONDS);
        host.dropConnections();
        // Ein Versuch: der Abbruch kommt durch, der nächste Auftrag verbindet neu.
        assertThatThrownBy(() -> session.files(files -> files.list("'KKR.JCL'")).get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThat(session.files(files -> files.list("'KKR.JCL'")).get(10, TimeUnit.SECONDS)).hasSize(2);
    }

    @Test
    void spoolNamesAreProbedOverParallelConnections() throws Exception {
        host.jesInterfaceLevel1();
        FtpProfile parallel = host.profile().withSpoolNaming(
                new com.aresstack.spaceharvester.deck.ftp.jes.SpoolNaming(
                        com.aresstack.spaceharvester.deck.ftp.jes.SpoolNaming.Mode.OFF, 2, false));
        FtpSession session = sessions.open(parallel, "Raumhafen", login("geheim"));
        session.connect().get(10, TimeUnit.SECONDS);
        int before = host.logins();
        java.util.Map<Integer, String> names = session.probeSpoolNames("JOB04700", 3).get(10, TimeUnit.SECONDS);
        assertThat(names).containsEntry(1, "JESMSGLG").containsEntry(2, "JESJCL").containsEntry(3, "SYSPRINT");
        assertThat(host.logins() - before).isEqualTo(2);
    }

    @Test
    void disconnectClosesConnectionsThatAreStillProbingSpoolNames() throws Exception {
        host.jesInterfaceLevel1();
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        FtpProfile parallel = host.profile().withSpoolNaming(
                new com.aresstack.spaceharvester.deck.ftp.jes.SpoolNaming(
                        com.aresstack.spaceharvester.deck.ftp.jes.SpoolNaming.Mode.OFF, 1, false));
        FtpSession session = sessions.open(parallel, "Raumhafen", login("geheim"));
        session.connect().get(10, TimeUnit.SECONDS);
        host.stallJesRetrieve(gate);
        try {
            var probing = session.probeSpoolNames("JOB04700", 3);
            assertThat(host.awaitStalledRetrieve(10)).as("der Host hängt in RETR").isTrue();
            session.disconnect();
            // Ohne Schließen der Nachlade-Verbindung bliebe die Abfrage hängen, bis der Host antwortet.
            assertThat(probing).failsWithin(10, TimeUnit.SECONDS);
        } finally {
            gate.countDown();
        }
    }

    @Test
    void onlyStreamModeIsAccepted() {
        assertThat(TransferOptions.TransferMode.BLOCK.supported()).isFalse();
        assertThat(TransferOptions.TransferMode.STREAM.supported()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TransferOptions(null, null, null,
                TransferOptions.TransferMode.COMPRESSED)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stream");
    }

    @Test
    void aRefusedLoginOnTheJesLaneAndOnProbesIsReportedAndNeverRetried() throws Exception {
        CommonsNetFtpConnector real = new CommonsNetFtpConnector();
        java.util.concurrent.atomic.AtomicInteger jesLogins = new java.util.concurrent.atomic.AtomicInteger();
        FtpConnector refusingJes = new FtpConnector() {
            @Override
            public FileConnection files(FtpProfile profile, String user, char[] password) throws java.io.IOException {
                return real.files(profile, user, password);
            }

            @Override
            public com.aresstack.spaceharvester.deck.ftp.jes.JesConnection jes(FtpProfile profile, String user,
                                                                               char[] password) throws java.io.IOException {
                jesLogins.incrementAndGet();
                throw new LoginRefusedException("Anmeldung als " + user + " abgelehnt: 530 PASS command failed", 530);
            }
        };
        FtpSessions guarded = new FtpSessions(refusingJes, background);
        List<String> refusals = new CopyOnWriteArrayList<>();
        guarded.onOpened(opened -> opened.onLoginRefused(refusals::add));
        FtpSession session = guarded.open(host.profile(), "Raumhafen", login("geheim"));
        session.connect().get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> session.jobs(jes -> jes.jobs(JobFilter.mine())).get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(LoginRefusedException.class);
        assertThat(jesLogins).as("abgelehnte JES-Anmeldung nicht wiederholt").hasValue(1);
        assertThat(refusals).singleElement().asString().contains("530");
        assertThat(session.state()).as("die Dataset-Verbindung steht weiter").isEqualTo(ConnectionState.CONNECTED);

        assertThatThrownBy(() -> session.probeSpoolNames("JOB04700", 1).get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(LoginRefusedException.class);
        assertThat(refusals).as("auch die Verbindung zum Nachladen meldet").hasSize(2);
        guarded.closeAll();
    }
}
