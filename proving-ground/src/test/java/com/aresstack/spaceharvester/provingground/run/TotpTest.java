package com.aresstack.spaceharvester.provingground.run;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TotpTest {

    /** RFC 6238 appendix B: the ASCII secret "12345678901234567890" in Base32. */
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void rfc6238TestVectors() {
        String uri = "otpauth://totp/Test:sb?secret=" + SECRET + "&digits=8&period=30&algorithm=SHA1";
        assertEquals("94287082", Totp.code(uri, Instant.ofEpochSecond(59)));
        assertEquals("07081804", Totp.code(uri, Instant.ofEpochSecond(1111111109)));
        assertEquals("14050471", Totp.code(uri, Instant.ofEpochSecond(1111111111)));
    }

    @Test
    void plainBase32UsesTheDefaults() {
        assertEquals("287082", Totp.code(SECRET, Instant.ofEpochSecond(59)));
        assertEquals("287082", Totp.code(SECRET.toLowerCase() + "==", Instant.ofEpochSecond(59)));
        assertArrayEquals("12345678901234567890".getBytes(StandardCharsets.US_ASCII), Totp.base32(SECRET));
        assertThrows(IllegalArgumentException.class, () -> Totp.code("not base32!", Instant.EPOCH));
    }
}
