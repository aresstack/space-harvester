package com.aresstack.spaceharvester.provingground.run;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TotpTest {

    /** RFC 6238 appendix B: the ASCII secret "12345678901234567890" in Base32. */
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    /** googleauth as WD4J uses it; the code keeps its leading zero ("07081804", RFC 6238 appendix B). */
    @Test
    void rfc6238TestVectors() {
        String uri = "otpauth://totp/Test:sb?secret=" + SECRET + "&digits=8&period=30&algorithm=SHA1";
        assertEquals("94287082", Totp.code(uri, Instant.ofEpochSecond(59)));
        assertEquals("07081804", Totp.code(uri, Instant.ofEpochSecond(1111111109)));
        assertEquals("14050471", Totp.code(uri, Instant.ofEpochSecond(1111111111)));
        String sha256 = "otpauth://totp/Test:sb?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA"
                + "&digits=8&algorithm=SHA256";
        assertEquals("46119246", Totp.code(sha256, Instant.ofEpochSecond(59)));
    }

    @Test
    void plainBase32UsesTheDefaults() {
        assertEquals("287082", Totp.code(SECRET, Instant.ofEpochSecond(59)));
        assertEquals("287082", Totp.code(SECRET.toLowerCase() + "==", Instant.ofEpochSecond(59)));
        assertThrows(IllegalArgumentException.class, () -> Totp.code("not base32!", Instant.EPOCH));
    }
}
