package com.aresstack.spaceharvester.app.proving;

import com.aresstack.spaceharvester.provingground.model.TestUser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeePassTestCredentialsTest {

    @Test
    void theOtpSecretComesFromTheUsualKeePassFields() {
        assertThat(KeePassTestCredentials.otp(Map.of("otp", "otpauth://totp/x?secret=ABC"))).isEqualTo(
                "otpauth://totp/x?secret=ABC");
        assertThat(KeePassTestCredentials.otp(Map.of("TimeOtp-Secret-Base32", "GEZDGNBV"))).isEqualTo("GEZDGNBV");
        assertThat(KeePassTestCredentials.otp(Map.of("totp seed", "GEZD"))).isEqualTo("GEZD");
        assertThat(KeePassTestCredentials.otp(Map.of("Notiz", "x", "otp", " "))).isNull();
        assertThat(KeePassTestCredentials.otp(null)).isNull();
    }

    /** Mehr Passwörter auf einer Seite (Angelo 28.11.): {{secret.PIN}} = Zusatzfeld „PIN“ des KeePass-Eintrags. */
    @Test
    void aFurtherPasswordComesFromTheEntrysFieldOfThatName() {
        assertThat(KeePassTestCredentials.field(Map.of("PIN", "4711", "otp", "GEZD"), "pin")).isEqualTo("4711");
        assertThat(KeePassTestCredentials.field(Map.of("PIN", "4711"), "Passwort")).as("kein Feld: Passwort gilt")
                .isNull();
        assertThat(KeePassTestCredentials.field(null, "PIN")).isNull();
    }

    @Test
    void usersWithoutEntryHaveNoCredentialsAndAnUnpairedVaultSaysSo() {
        KeePassTestCredentials credentials = new KeePassTestCredentials(() -> null);
        assertThat(credentials.of(new TestUser("anonym", "", ""))).isNull();
        assertThatThrownBy(() -> credentials.of(new TestUser("sb", "Test/SB", "")))
                .hasMessage(KeePassTestCredentials.NOT_PAIRED);
    }
}
