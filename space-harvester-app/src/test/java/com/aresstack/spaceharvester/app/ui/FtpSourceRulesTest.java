package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.deck.ftp.FtpProfile;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FtpSourceRulesTest {

    @Test
    void mvsStartPointsAreUnquotedAndUpperCase() {
        assertThat(FtpSourceRules.startPoint("'hlq.app.src'", true)).isEqualTo("HLQ.APP.SRC");
        assertThat(FtpSourceRules.startPoint("  /u/user/src ", false)).isEqualTo("/u/user/src");
        assertThatThrownBy(() -> FtpSourceRules.startPoint("''", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FtpSourceRules.startPoint("/", false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FtpSourceRules.startPoint(null, false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void coveredWhenAStartPointIsTheSameOrAbove() {
        assertThat(FtpSourceRules.covered("HLQ", "HLQ.APP.SRC", true)).isTrue();
        assertThat(FtpSourceRules.covered("'hlq.app.src'", "HLQ.APP.SRC", true)).isTrue();
        assertThat(FtpSourceRules.covered("HLQ.APP.SRC", "HLQ.APP.SRC(PGM)", true)).isTrue();
        assertThat(FtpSourceRules.covered("HLQ.AP", "HLQ.APP.SRC", true)).isFalse();
        assertThat(FtpSourceRules.covered("OTHER; HLQ.LIB", "HLQ.APP", true)).isFalse();
        assertThat(FtpSourceRules.covered(null, "HLQ.APP", true)).isFalse();
        assertThat(FtpSourceRules.covered("/u/user/", "/u/user/src", false)).isTrue();
        assertThat(FtpSourceRules.covered("/u/us", "/u/user/src", false)).isFalse();
        assertThat(FtpSourceRules.covered("/", "/u/user", false)).isTrue();
    }

    @Test
    void appendsWithoutTouchingExistingStartPoints() {
        assertThat(FtpSourceRules.withStartPoint("A.B, C.D", "E.F")).isEqualTo("A.B; C.D; E.F");
        assertThat(FtpSourceRules.withStartPoint(null, "E.F")).isEqualTo("E.F");
        assertThat(FtpSourceRules.withStartPoint("  ", "E.F")).isEqualTo("E.F");
    }

    @Test
    void anFtpSourceServesTheSessionWithoutNavigationFill() {
        FtpProfile profile = FtpProfile.of("mvs", 21);
        SourceSettingsConfig source = new SourceSettingsConfig("ftp", "ftp", "FTP", null, null,
                Map.of("host", "mvs", "credentialRef", "keepass:Account"));
        assertThat(FtpNavigationFill.serves(source, profile, "Account", "USER", true)).isTrue();
        assertThat(FtpNavigationFill.matches(source, profile, "Account", "USER", true)).isFalse();
        assertThat(FtpNavigationFill.serves(source, profile, "Other", "USER", true)).isFalse();
        assertThat(FtpNavigationFill.sameEndpoint(source, FtpProfile.of("mvs", 2121))).isFalse();
    }
}
