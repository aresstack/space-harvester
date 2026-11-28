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

    @Test
    void usersWithoutEntryHaveNoCredentialsAndAnUnpairedVaultSaysSo() {
        KeePassTestCredentials credentials = new KeePassTestCredentials(() -> null);
        assertThat(credentials.of(new TestUser("anonym", "", ""))).isNull();
        assertThatThrownBy(() -> credentials.of(new TestUser("sb", "Test/SB", "")))
                .hasMessage(KeePassTestCredentials.NOT_PAIRED);
    }
}
