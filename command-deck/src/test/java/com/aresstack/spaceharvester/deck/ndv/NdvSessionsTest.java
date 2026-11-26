package com.aresstack.spaceharvester.deck.ndv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NdvSessionsTest {

    private final FakeNdvServer server = FakeNdvServer.sample().pageSize(3);
    private final List<Runnable> queued = new ArrayList<>();
    private final NdvSessions sessions = new NdvSessions(server, queued::add);
    private final AtomicInteger logins = new AtomicInteger();

    private NdvSessions.LoginSource login(String password) {
        return () -> {
            logins.incrementAndGet();
            return new ServerCredentials(new ServerAccount("Host Lager", "10.0.0.7", "pilot"), password.toCharArray());
        };
    }

    private NdvSession connected() {
        NdvSession session = sessions.open(new NdvProfile("10.0.0.7", NdvProfile.DEFAULT_PORT), "Host Lager",
                login("geheim"));
        queued.forEach(Runnable::run);
        queued.clear();
        return session;
    }

    @Test
    void opensInTheBackgroundAndReportsTheSessionAtOnce() {
        List<NdvSession> opened = new ArrayList<>();
        sessions.onOpened(opened::add);
        NdvSession session = sessions.open(new NdvProfile("10.0.0.7", 2700), "Host Lager", login("geheim"));

        assertThat(opened).containsExactly(session);
        assertThat(session.state()).isEqualTo(NdvState.CONNECTING);
        assertThat(server.connects()).as("erst der Hintergrund verbindet").isZero();
        assertThat(logins).as("das Passwort liest erst der Hintergrund").hasValue(0);

        queued.forEach(Runnable::run);
        assertThat(session.state()).isEqualTo(NdvState.CONNECTED);
        assertThat(session.user()).isEqualTo("PILOT");
        assertThat(session.label()).isEqualTo("Host Lager");
        assertThat(server.lastPassword()).as("Passwort nach dem Verbinden gelöscht").containsOnly('\0');
    }

    @Test
    void aWrongPasswordFailsWithTheServersReason() {
        NdvSession session = sessions.open(new NdvProfile("10.0.0.7", 2700), "", login("falsch"));
        List<NdvState> seen = new ArrayList<>();
        session.observe(changed -> seen.add(changed.state()));
        queued.forEach(Runnable::run);

        assertThat(session.state()).isEqualTo(NdvState.FAILED);
        assertThat(session.failure()).hasValueSatisfying(reason -> assertThat(reason).contains("NAT0838"));
        assertThat(seen).containsExactly(NdvState.FAILED);
    }

    @Test
    void listsLibrariesWithAPattern() throws IOException {
        NdvSession session = connected();

        assertThat(session.libraries("")).containsExactly("LAGER", "KASSE", "SYSTEM");
        assertThat(session.libraries("la*")).containsExactly("LAGER");
    }

    @Test
    void listsObjectsPageByPageAndStopsWhenAsked() throws IOException {
        NdvSession session = connected();
        List<Integer> pages = new ArrayList<>();

        int all = session.objects("lager", "*", (objects, total) -> pages.add(objects.size()));
        assertThat(all).isEqualTo(7);
        assertThat(pages).containsExactly(3, 3, 1);

        List<NdvObject> first = new ArrayList<>();
        int stopped = session.objects("LAGER", "*", (objects, total) -> !first.addAll(objects));
        assertThat(stopped).isEqualTo(3);
        assertThat(first).extracting(NdvObject::displayName).containsExactly("LAGER001", "LAGER002", "LAGERN01");
    }

    @Test
    void readsSourceAndShowsDdmsByTheirLongName() throws IOException {
        NdvSession session = connected();
        List<NdvObject> objects = new ArrayList<>();
        session.objects("LAGER", "*", (page, total) -> objects.addAll(page));

        NdvObject program = objects.getFirst();
        assertThat(program.path()).isEqualTo("LAGER/LAGER001.NSP");
        assertThat(session.read(program)).contains("READ ARTIKEL BY NUMMER");
        NdvObject ddm = objects.getLast();
        assertThat(ddm.displayName()).isEqualTo("ARTIKEL");
        assertThat(session.read(ddm)).contains("FILE: 120");
        assertThat(server.writes()).as("Lesen schreibt nichts").isEmpty();
    }

    @Test
    void reconnectsOnceWhenTheConnectionDropsAndRepeatsTheCall() throws IOException {
        NdvSession session = connected();
        server.dropConnections();

        assertThat(session.libraries("*")).contains("LAGER");
        assertThat(server.connects()).isEqualTo(2);
        assertThat(logins).as("Passwort für den neuen Aufbau neu gelesen").hasValue(2);
        assertThat(session.state()).isEqualTo(NdvState.CONNECTED);
    }

    @Test
    void rejectsAChangedPrincipalOnReconnect() throws IOException {
        var principal = new java.util.concurrent.atomic.AtomicReference<>("pilot");
        NdvSession session = sessions.open(new NdvProfile("10.0.0.7", NdvProfile.DEFAULT_PORT), "Host Lager",
                () -> new ServerCredentials(new ServerAccount("Host Lager", "10.0.0.7", principal.get()),
                        "geheim".toCharArray()));
        queued.forEach(Runnable::run);
        queued.clear();
        assertThat(session.user()).isEqualTo("PILOT");
        server.dropConnections();
        principal.set("other");
        assertThatThrownBy(() -> session.libraries("*")).isInstanceOf(IOException.class)
                .hasMessageContaining("Benutzer geändert");
        assertThat(server.connects()).isEqualTo(1);
        assertThat(session.state()).isEqualTo(NdvState.FAILED);
    }

    @Test
    void failsWithAReasonWhenReconnectingFailsToo() {
        NdvSession session = connected();
        server.dropConnections();
        server.refuse("Verbindung abgelehnt: 10.0.0.7:2700");

        assertThatThrownBy(() -> session.libraries("*")).isInstanceOf(IOException.class)
                .hasMessageContaining("abgelehnt");
        assertThat(session.state()).isEqualTo(NdvState.FAILED);
        assertThat(session.failure()).hasValueSatisfying(reason -> assertThat(reason).contains("abgelehnt"));

        server.refuse(null);
        sessions.reconnect(session);
        queued.forEach(Runnable::run);
        assertThat(session.state()).isEqualTo(NdvState.CONNECTED);
    }

    @Test
    void afterDisconnectNothingConnectsBySelf() {
        NdvSession session = connected();
        session.disconnect();

        assertThat(session.state()).isEqualTo(NdvState.DISCONNECTED);
        assertThat(server.openConnections()).isZero();
        assertThatThrownBy(() -> session.libraries("*")).hasMessageContaining("getrennt");
        assertThat(server.connects()).isEqualTo(1);
    }

    @Test
    void aDisconnectDuringACallLeavesNoConnectionOpen() throws Exception {
        java.util.concurrent.CountDownLatch inCall = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        NdvConnector slow = (profile, user, password) -> {
            NdvConnection real = server.connect(profile, user, password);
            return new NdvConnection() {
                public List<String> libraries(String filter) throws IOException {
                    inCall.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return real.libraries(filter);
                }
                public int objects(String library, String filter, Page page) throws IOException {
                    return real.objects(library, filter, page);
                }
                public String read(NdvObject object) throws IOException {
                    return real.read(object);
                }
                public void write(NdvObject object, String source) throws IOException {
                    real.write(object, source);
                }
                public boolean open() {
                    return real.open();
                }
                public void close() {
                    real.close();
                }
            };
        };
        NdvSessions own = new NdvSessions(slow, Runnable::run);
        NdvSession session = own.open(new NdvProfile("10.0.0.7", 2700), "", login("geheim"));
        Thread call = Thread.ofVirtual().start(() -> {
            try {
                session.libraries("*");
            } catch (IOException expected) {
                // Verbindung beim Trennen geschlossen
            }
        });
        inCall.await();
        session.disconnect();
        release.countDown();
        call.join();

        assertThat(session.state()).isEqualTo(NdvState.DISCONNECTED);
        assertThat(server.openConnections()).as("keine Verbindung bleibt stehen").isZero();
        assertThatThrownBy(() -> session.libraries("*")).hasMessageContaining("getrennt");
    }

    @Test
    void writesOnlyOnRequest() throws IOException {
        NdvSession session = connected();
        List<NdvObject> objects = new ArrayList<>();
        session.objects("KASSE", "KASSE001", (page, total) -> objects.addAll(page));

        session.write(objects.getFirst(), "* neu\nEND");
        assertThat(server.writes()).containsExactly("KASSE/KASSE001");
        assertThat(server.source("KASSE", "KASSE001")).isEqualTo("* neu\nEND");
    }

    @Test
    void closeAllDisconnectsEverySession() {
        connected();
        connected();
        assertThat(server.openConnections()).isEqualTo(2);

        sessions.closeAll();
        assertThat(server.openConnections()).isZero();
        assertThat(sessions.all()).extracting(NdvSession::state).containsOnly(NdvState.DISCONNECTED);
    }

    @Test
    void theProfileNeedsAHostAndAPort() {
        assertThatThrownBy(() -> new NdvProfile(" ", 2700)).hasMessageContaining("Host");
        assertThatThrownBy(() -> new NdvProfile("h", 0)).hasMessageContaining("Port");
        assertThat(new NdvProfile(" host ", 2700).label()).isEqualTo("host:2700");
    }
}
