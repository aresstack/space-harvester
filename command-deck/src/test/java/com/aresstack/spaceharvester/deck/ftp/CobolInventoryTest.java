package com.aresstack.spaceharvester.deck.ftp;

import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.ftp.commonsnet.CommonsNetFtpConnector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CobolInventoryTest {
    @Test
    void scansExactPdsAndExplicitCopybookLibrariesWithoutRetrievalOrWrites() throws Exception {
        try (var host = FakeMvsFtpHost.sample(); var executor = Executors.newCachedThreadPool()) {
            host.member("KKR.SRC", "PGM1", "       STOP RUN.");
            host.member("KKR.SRC2", "OUTSIDE", "       STOP RUN.");
            host.member("OTHER.CPY", "BOOK", "       01 FIELD PIC X.");
            var session = new FtpSessions(new CommonsNetFtpConnector(), executor).open(host.profile(), "Testhost",
                    () -> new ServerCredentials(new ServerAccount("Testhost", host.host(), "KKR"),
                            "geheim".toCharArray()));
            try {
                session.connect().get();
                var result = CobolInventory.forSession(session).scan(
                        new CobolAnalysisArea("KKR.SRC", List.of("OTHER.CPY")), () -> false, ignored -> { });
                assertThat(result.complete()).as(result.failures().toString()).isTrue();
                assertThat(result.members()).contains(
                        new CobolInventory.Member("'KKR.SRC(PGM1)'", true, false),
                        new CobolInventory.Member("'OTHER.CPY(BOOK)'", false, true));
                assertThat(result.members()).noneMatch(member -> member.path().contains("SRC2"));
                assertThat(host.commands()).noneMatch(command -> command.matches("(?i)^(RETR|STOR|DELE|MKD|RMD).*"));
                var prefix = CobolInventory.forSession(session).scan(
                        new CobolAnalysisArea("KKR", List.of()), () -> false, ignored -> { });
                assertThat(prefix.complete()).as(prefix.failures().toString()).isTrue();
                assertThat(prefix.members()).extracting(CobolInventory.Member::path)
                        .contains("'KKR.SRC(PGM1)'", "'KKR.SRC2(OUTSIDE)'", "'KKR.JCL(BUILD)'");
                assertThat(prefix.members()).noneMatch(member -> member.path().contains("OTHER.CPY"));
                var missing = CobolInventory.forSession(session).scan(
                        new CobolAnalysisArea("KKR.MISSING", List.of()), () -> false, ignored -> { });
                assertThat(missing.complete()).isFalse();
                assertThat(missing.failures()).containsKey("'KKR.MISSING'");
            } finally {
                session.disconnect();
            }
        }
    }

    @Test
    void reportsFailedCatalogAndRejectsUnexpectedMemberPaths() {
        var source = new CobolInventory.Source() {
            @Override
            public List<String> descendants(String home) throws IOException {
                throw new IOException("Permission denied");
            }

            @Override
            public List<RemoteEntry> members(String dataset) {
                return List.of(RemoteEntry.file("WRONG", "'OTHER.SRC(WRONG)'"));
            }
        };
        var result = new CobolInventory(source).scan(new CobolAnalysisArea("KKR.SRC", List.of()),
                () -> false, ignored -> { });
        assertThat(result.complete()).isFalse();
        assertThat(result.members()).isEmpty();
        assertThat(result.failures()).containsKeys("Katalog 'KKR.SRC'", "'KKR.SRC'");
    }

    @Test
    void cancelsBetweenDatasetsAndKeepsCompletedMembers() {
        var cancelled = new AtomicBoolean();
        var requests = new ArrayList<String>();
        var source = new CobolInventory.Source() {
            @Override
            public List<String> descendants(String home) {
                return List.of("'KKR.SRC.SUB'");
            }

            @Override
            public List<RemoteEntry> members(String dataset) {
                requests.add(dataset);
                cancelled.set(true);
                return List.of(RemoteEntry.file("PGM", MvsPath.absolute(MvsPath.unquote(dataset) + "(PGM)")));
            }
        };
        var result = new CobolInventory(source).scan(new CobolAnalysisArea("KKR.SRC", List.of()),
                cancelled::get, ignored -> { });
        assertThat(result.cancelled()).isTrue();
        assertThat(result.complete()).isFalse();
        assertThat(result.members()).hasSize(1);
        assertThat(requests).containsExactly("'KKR.SRC'");
    }
}
