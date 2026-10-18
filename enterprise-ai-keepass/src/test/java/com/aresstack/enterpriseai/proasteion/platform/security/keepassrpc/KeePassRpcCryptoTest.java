package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

public class KeePassRpcCryptoTest {

    private final SecureRandom random = new SecureRandom();
    private final char[] key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".toCharArray();

    @Test
    public void encryptDecryptRoundTripWithUmlauts() throws Exception {
        byte[] plain = "{\"title\":\"Schlüssel\"}".getBytes(StandardCharsets.UTF_8);
        KeePassRpcCrypto.Sealed sealed = KeePassRpcCrypto.encrypt(key, plain, random);
        assertEquals(16, sealed.iv.length);
        assertEquals(20, sealed.hmac.length);
        assertArrayEquals(plain, KeePassRpcCrypto.decrypt(key, sealed));
    }

    @Test
    public void tamperedCiphertextIsRejectedByHmac() throws Exception {
        KeePassRpcCrypto.Sealed sealed = KeePassRpcCrypto.encrypt(key, "x".getBytes(StandardCharsets.UTF_8), random);
        sealed.ciphertext[0] ^= 1;
        try {
            KeePassRpcCrypto.decrypt(key, sealed);
            fail();
        } catch (KeePassRpcException e) {
            assertEquals(KeePassRpcException.Kind.PROTOCOL, e.kind());
        }
    }

    @Test
    public void malformedKeyIsAuthFailureWithoutEchoingTheKey() {
        char[] bad = Arrays.copyOf(key, 64);
        bad[10] = 'z';
        try {
            KeePassRpcCrypto.keyBytes(bad);
            fail();
        } catch (KeePassRpcException e) {
            assertEquals(KeePassRpcException.Kind.AUTH_FAILED, e.kind());
            assertEquals(-1, e.getMessage().indexOf(new String(bad)));
        }
    }

    @Test
    public void challengeResponseMatchesTheMainframeMateFormula() {
        // MainframeMate: bytesToHex(sha256str("1" + srpKey + sc + cc))
        String expected = KeePassRpcCrypto.hex(KeePassRpcCrypto.sha256(
                ("1" + new String(key) + "123" + "456").getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, KeePassRpcCrypto.challengeResponse("1", key, "123", "456"));
        assertNotEquals(expected, KeePassRpcCrypto.challengeResponse("0", key, "123", "456"));
    }

    @Test
    public void hexMatchesGoUppercaseWithoutLeadingZeros() {
        assertEquals("ABC", KeePassRpcCrypto.toHex(new java.math.BigInteger("0abc", 16)));
        assertEquals("00ff", KeePassRpcCrypto.hex(new byte[] {0, (byte) 0xff}));
    }

    @Test
    public void srpProofDoesNotRevealThePassword() throws Exception {
        KeePassRpcCrypto.SrpClient client = new KeePassRpcCrypto.SrpClient(random);
        KeePassRpcCrypto.Proof proof = client.prove("abcd", "1F", "geheim".toCharArray());
        assertEquals("Proof[***]", proof.toString());
        assertEquals(64, proof.sessionKey().length);
    }

    @Test
    public void srpRejectsZeroB() {
        try {
            new KeePassRpcCrypto.SrpClient(random).prove("abcd", KeePassRpcCrypto.toHex(KeePassRpcCrypto.N),
                    "x".toCharArray());
            fail();
        } catch (KeePassRpcException e) {
            assertEquals(KeePassRpcException.Kind.PROTOCOL, e.kind());
        }
    }
}
