package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.deck.ftp.FtpProfile;
import com.aresstack.spaceharvester.deck.ftp.RemoteEntry;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FtpNavigationFillTest {

    @Test
    void onlyMatchesOptedInSourcesWithTheSameEndpointAndAccount() {
        FtpProfile profile = FtpProfile.of("MVS", 21);
        SourceSettingsConfig source = source(Map.of("host", "mvs", "navigationFill", "true",
                "credentialRef", "keepass:Account"));
        assertThat(FtpNavigationFill.matches(source, profile, "Account", "USER", true)).isTrue();
        assertThat(FtpNavigationFill.matches(source, profile, "Other", "USER", true)).isFalse();
        assertThat(FtpNavigationFill.matches(source, FtpProfile.of("other", 21), "Account", "USER", true)).isFalse();
        assertThat(FtpNavigationFill.matches(source, FtpProfile.of("mvs", 22), "Account", "USER", true)).isFalse();
        assertThat(FtpNavigationFill.matches(source(Map.of("host", "mvs", "credentialRef", "keepass:Account")),
                profile, "Account", "USER", true)).isFalse();
    }

    @Test
    void usesTheProviderDefaultPortsIncludingImplicitTls() {
        SourceSettingsConfig implicit = source(Map.of("host", "mvs", "tls", "implicit", "navigationFill", "true",
                "credentialRef", "keepass:Account"));
        assertThat(FtpNavigationFill.matches(implicit, FtpProfile.of("mvs", 990), "Account", "USER", true)).isTrue();
        assertThat(FtpNavigationFill.matches(implicit, FtpProfile.of("mvs", 21), "Account", "USER", true)).isFalse();
        SourceSettingsConfig explicitPort = source(Map.of("host", "mvs", "port", "2121", "navigationFill", "true",
                "credentialRef", "keepass:Account"));
        assertThat(FtpNavigationFill.matches(explicitPort, FtpProfile.of("mvs", 2121), "Account", "USER", true))
                .isTrue();
        assertThat(FtpNavigationFill.matches(source(Map.of("host", "", "navigationFill", "true",
                "credentialRef", "keepass:Account")), FtpProfile.of("mvs", 21), "Account", "USER", true)).isFalse();
    }

    @Test
    void selectsOnlyDirectFilesMembersAndConfirmedSequentialDatasets() {
        RemoteEntry sequential = RemoteEntry.directory("SEQ", "'HLQ.SEQ'").withDetails(
                new RemoteEntry.Details("FB", 80, 3200, "PS", "", 0, ""), -1, Optional.empty());
        RemoteEntry member = RemoteEntry.file("PGM", "'HLQ.PDS(PGM)'");
        assertThat(FtpNavigationFill.directFiles(true, List.of(
                RemoteEntry.directory("PDS", "'HLQ.PDS'"), sequential, member, member)))
                .containsExactly("HLQ.SEQ", "HLQ.PDS(PGM)");
        assertThat(FtpNavigationFill.directFiles(false, List.of(
                RemoteEntry.directory("dir", "/dir"), RemoteEntry.file("file", "/file"))))
                .containsExactly("/file");
    }

    private static SourceSettingsConfig source(Map<String, String> options) {
        return new SourceSettingsConfig("ftp", "ftp", "FTP", null, null, options);
    }
}