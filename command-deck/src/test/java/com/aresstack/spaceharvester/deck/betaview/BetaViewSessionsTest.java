package com.aresstack.spaceharvester.deck.betaview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

/** Der Anwendungsfall ohne Server: Anmeldung, Zustände, Kennwort und das richtige Dokument auf dem Server. */
class BetaViewSessionsTest {

    private static final Executor DIRECT = Runnable::run;
    private static final BetaViewProfile PROFILE = new BetaViewProfile(URI.create("https://beta.example.org/bv/"),
            "pilot");

    /** Merkt sich die Aufrufe; „öffnet“ Dokumente über ihre Aktion. */
    private static class Recording implements BetaViewConnection {
        final List<String> calls = new ArrayList<>();

        @Override
        public ResultTable search(SearchFilter filter) {
            calls.add("search");
            return ResultTable.empty();
        }

        @Override
        public OpenedDocument open(String action) {
            calls.add("open " + action);
            DocumentTab tab = new DocumentTab("", "", "", action, "", action, true);
            return new OpenedDocument(tab, List.of(tab), 2, "", List.of());
        }

        @Override
        public String page(int page) {
            calls.add("page " + page);
            return "Seite " + page;
        }

        @Override
        public DownloadChoices downloadChoices() {
            return new DownloadChoices(2, false);
        }

        @Override
        public DownloadedFile download(DownloadOptions options) {
            calls.add("download");
            return new DownloadedFile(new byte[] {1}, "application/pdf");
        }

        @Override
        public List<InfoLine> properties() {
            return List.of();
        }

        @Override
        public NameAndDescription bookmarkProposal() {
            return new NameAndDescription("", "");
        }

        @Override
        public void bookmark(NameAndDescription bookmark) {
        }

        @Override
        public List<Bookmark> bookmarks() {
            return List.of();
        }

        @Override
        public List<SavedSearch> savedSearches() {
            return List.of();
        }

        @Override
        public ResultTable run(SavedSearch search) {
            return ResultTable.empty();
        }

        @Override
        public void saveSearch(NameAndDescription search) {
        }

        @Override
        public List<DocumentTab> openTabs() {
            return List.of();
        }

        @Override
        public void close(DocumentTab document) {
            calls.add("close " + document.key());
        }

        @Override
        public void closeAll() {
        }

        @Override
        public String notes() {
            return "";
        }

        @Override
        public String markedRanges() {
            return "";
        }

        @Override
        public String searchText(String query) {
            calls.add("searchText " + query);
            return "";
        }

        @Override
        public String printInfo() {
            return "";
        }
    }

    @Test
    void logsInAndWipesThePassword() {
        Recording connection = new Recording();
        char[][] seen = new char[1][];
        BetaViewSessions sessions = new BetaViewSessions((profile, password) -> {
            seen[0] = password;
            assertThat(new String(password)).isEqualTo("geheim");
            return connection;
        }, DIRECT);
        List<ConnectionState> states = new ArrayList<>();
        sessions.onOpened(session -> session.observe(changed -> states.add(changed.state())));
        BetaViewSession session = sessions.open(PROFILE, "geheim".toCharArray());
        assertThat(session.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(states).containsExactly(ConnectionState.CONNECTED);
        assertThat(seen[0]).containsOnly('\0');
    }

    @Test
    void aFailedLoginKeepsTheReason() {
        BetaViewSessions sessions = new BetaViewSessions((profile, password) -> {
            throw new IOException("Anmeldung abgelehnt: Benutzer oder Kennwort falsch");
        }, DIRECT);
        BetaViewSession session = sessions.open(PROFILE, "x".toCharArray());
        assertThat(session.state()).isEqualTo(ConnectionState.FAILED);
        assertThat(session.failure()).contains("Anmeldung abgelehnt: Benutzer oder Kennwort falsch");
        assertThat(sessions.all()).containsExactly(session);
    }

    @Test
    void withoutPasswordNothingIsSent() {
        List<String> logins = new ArrayList<>();
        BetaViewSessions sessions = new BetaViewSessions((profile, password) -> {
            logins.add("login");
            return new Recording();
        }, DIRECT);
        BetaViewSession session = sessions.open(PROFILE, new char[0]);
        assertThat(session.state()).isEqualTo(ConnectionState.FAILED);
        assertThat(logins).isEmpty();
    }

    @Test
    void reopensTheDocumentWhenTheServerShowsAnother() throws Exception {
        Recording connection = new Recording();
        BetaViewSession session = new BetaViewSessions((profile, password) -> connection, DIRECT)
                .open(PROFILE, "p".toCharArray());
        OpenedDocument first = session.open("open.action?index=0").get();
        OpenedDocument second = session.open("open.action?index=1").get();
        connection.calls.clear();

        assertThat(session.page(second.document(), 2).get()).isEqualTo("Seite 2");
        assertThat(connection.calls).containsExactly("page 2");

        assertThat(session.page(first.document(), 1).get()).isEqualTo("Seite 1");
        assertThat(connection.calls).containsExactly("page 2", "open open.action?index=0", "page 1");

        session.download(first.document(), DownloadOptions.quick()).get();
        assertThat(connection.calls).endsWith("page 1", "download");
    }

    @Test
    void afterDisconnectEveryCallFails() {
        BetaViewSession session = new BetaViewSessions((profile, password) -> new Recording(), DIRECT)
                .open(PROFILE, "p".toCharArray());
        session.disconnect();
        assertThat(session.state()).isEqualTo(ConnectionState.DISCONNECTED);
        assertThatThrownBy(() -> session.savedSearches().get()).isInstanceOf(ExecutionException.class)
                .hasMessageContaining("Nicht angemeldet");
    }

    @Test
    void callsRunInTheOrderTheyWereRequested() throws Exception {
        List<String> done = java.util.Collections.synchronizedList(new ArrayList<>());
        BetaViewConnection connection = new Recording() {
            @Override
            public void saveSearch(NameAndDescription search) {
                done.add(search.name());
            }
        };
        try (var background = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            BetaViewSession queued = new BetaViewSession("q", PROFILE, background);
            queued.connected(connection);
            List<java.util.concurrent.CompletableFuture<Void>> calls = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                expected.add("S" + i);
                calls.add(queued.saveSearch(new NameAndDescription("S" + i, "")));
            }
            java.util.concurrent.CompletableFuture.allOf(calls.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(done).containsExactlyElementsOf(expected);
        }
    }
}
