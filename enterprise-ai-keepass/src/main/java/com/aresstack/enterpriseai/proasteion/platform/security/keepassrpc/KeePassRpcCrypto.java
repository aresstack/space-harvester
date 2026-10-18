package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Kryptografie des KeePassRPC-Protokolls, unverändert aus MainframeMate übernommen
 * ({@code KeePassRpcClient}, {@code KeePassRpcPairingDialog}; dort gegen echtes KeePass erprobt) und nur so
 * umgebaut, dass Passwort und Schlüssel als {@code char[]} statt {@code String} verarbeitet werden.
 *
 * <ul>
 *   <li>SRP-6a mit dem 512-Bit-Modulus aus KeePassRPC {@code SRP.cs} (nicht RFC 5054), {@code g = 2},
 *       fest kodiertem {@code k}; Hex-Darstellungen groß und ohne führende Nullen wie Go {@code %X}.</li>
 *   <li>Key-Challenge-Response (KCR) mit dem gespeicherten Sitzungsschlüssel.</li>
 *   <li>JSON-RPC verschlüsselt mit AES/CBC/PKCS5 und dem Sitzungsschlüssel (32 Byte), "HMAC" als
 *       {@code SHA1(SHA1(key) || ciphertext || iv)}.</li>
 * </ul>
 */
final class KeePassRpcCrypto {

    /** 512-Bit-Primzahl aus KeePassRPC {@code SRP.cs}. */
    static final BigInteger N = new BigInteger(
            "d4c7f8a2b32c11b8fba9581ec4ba4f1b04215642ef7355e37c0fc0443ef756ea"
                    + "2c6b8eeb755a1c723027663caa265ef785b8ff6a9b35227a52d86633dbdfca43", 16);
    static final BigInteger G = BigInteger.valueOf(2);
    /** {@code k = SHA1(N | pad(g))}, im KeePassRPC-Server fest kodiert. */
    static final BigInteger K = new BigInteger("b7867f1299da8cc24ab93e08986ebc4d6a478ad0", 16);

    private KeePassRpcCrypto() {
    }

    // ── SRP (Pairing) ───────────────────────────────────────────────────

    /** Clientseitiger SRP-Zustand einer Pairing-Verbindung. */
    static final class SrpClient {

        private final BigInteger a;
        private final String aHex;

        SrpClient(SecureRandom random) {
            this.a = new BigInteger(256, random);
            this.aHex = toHex(G.modPow(a, N));
        }

        /** Öffentlicher Wert {@code A} für {@code identifyToServer}. */
        String aHex() {
            return aHex;
        }

        /**
         * Berechnet den Beweis {@code M} und das Geheimnis {@code S} aus Salt, Server-Wert {@code B} und
         * Einmal-Passwort.
         */
        Proof prove(String salt, String bRaw, char[] password) throws KeePassRpcException {
            String bPadded = bRaw.length() % 2 != 0 ? "0" + bRaw : bRaw;
            BigInteger b = new BigInteger(bPadded, 16);
            if (b.mod(N).signum() == 0) {
                throw new KeePassRpcException(KeePassRpcException.Kind.PROTOCOL, "SRP: ungültiger Server-Wert B");
            }
            String bHex = toHex(b);
            BigInteger u = new BigInteger(1, sha256(utf8(aHex + bHex)));
            if (u.signum() == 0) {
                throw new KeePassRpcException(KeePassRpcException.Kind.PROTOCOL, "SRP: ungültiger u-Wert");
            }
            byte[] saltAndPassword = concat(utf8(salt), utf8(password));
            BigInteger x = new BigInteger(1, sha256(saltAndPassword));
            Arrays.fill(saltAndPassword, (byte) 0);

            BigInteger diff = b.subtract(K.multiply(G.modPow(x, N)).mod(N)).mod(N);
            BigInteger s = diff.modPow(a.add(u.multiply(x)), N);
            String sHex = toHex(s);
            String mHex = hex(sha256(utf8(aHex + bHex + sHex)));
            return new Proof(aHex, mHex, sHex);
        }
    }

    /** Ergebnis von {@link SrpClient#prove}. */
    static final class Proof {

        private final String aHex;
        private final String mHex;
        private final String sHex;

        Proof(String aHex, String mHex, String sHex) {
            this.aHex = aHex;
            this.mHex = mHex;
            this.sHex = sHex;
        }

        String mHex() {
            return mHex;
        }

        /** Erwarteter Server-Beweis {@code M2 = SHA256(A || M || S)}. */
        String expectedM2() {
            return hex(sha256(utf8(aHex + mHex.toLowerCase() + sHex)));
        }

        /** Sitzungsschlüssel, den KeePassRPC speichert: {@code hex(SHA256(S))}, 64 Hex-Zeichen klein. */
        char[] sessionKey() {
            return hex(sha256(utf8(sHex))).toCharArray();
        }

        @Override
        public String toString() {
            return "Proof[***]";
        }
    }

    // ── Key-Challenge-Response ──────────────────────────────────────────

    /** Neue Client-Challenge {@code cc}: 32 Zufallsbytes als Dezimalzahl (wie MainframeMate). */
    static String newClientChallenge(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return new BigInteger(1, bytes).toString();
    }

    /** {@code hex(SHA256(prefix || key || sc || cc))}; Client sendet Präfix "1", Server antwortet mit "0". */
    static String challengeResponse(String prefix, char[] sessionKey, String sc, String cc) {
        byte[] input = concat(utf8(prefix), utf8(sessionKey), utf8(sc + cc));
        try {
            return hex(sha256(input));
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    // ── Verschlüsseltes JSON-RPC ────────────────────────────────────────

    /** Verschlüsselte Nachricht: Ciphertext, IV und HMAC. */
    static final class Sealed {

        final byte[] ciphertext;
        final byte[] iv;
        final byte[] hmac;

        Sealed(byte[] ciphertext, byte[] iv, byte[] hmac) {
            this.ciphertext = ciphertext;
            this.iv = iv;
            this.hmac = hmac;
        }
    }

    static Sealed encrypt(char[] sessionKey, byte[] plaintext, SecureRandom random) throws KeePassRpcException {
        byte[] key = keyBytes(sessionKey);
        try {
            byte[] iv = new byte[16];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new Sealed(ciphertext, iv, hmac(key, ciphertext, iv));
        } catch (GeneralSecurityException e) {
            throw new KeePassRpcException(KeePassRpcException.Kind.PROTOCOL,
                    "Verschlüsselung fehlgeschlagen: " + e.getClass().getSimpleName(), e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    static byte[] decrypt(char[] sessionKey, Sealed sealed) throws KeePassRpcException {
        byte[] key = keyBytes(sessionKey);
        try {
            if (!MessageDigest.isEqual(sealed.hmac, hmac(key, sealed.ciphertext, sealed.iv))) {
                throw new KeePassRpcException(KeePassRpcException.Kind.PROTOCOL, "HMAC-Prüfung fehlgeschlagen");
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(sealed.iv));
            return cipher.doFinal(sealed.ciphertext);
        } catch (GeneralSecurityException e) {
            throw new KeePassRpcException(KeePassRpcException.Kind.PROTOCOL,
                    "Entschlüsselung fehlgeschlagen: " + e.getClass().getSimpleName(), e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** {@code SHA1(SHA1(key) || ciphertext || iv)}. */
    static byte[] hmac(byte[] key, byte[] ciphertext, byte[] iv) {
        MessageDigest sha1 = digest("SHA-1");
        byte[] keyHash = sha1.digest(key);
        sha1.reset();
        sha1.update(keyHash);
        sha1.update(ciphertext);
        sha1.update(iv);
        return sha1.digest();
    }

    // ── Hilfen ──────────────────────────────────────────────────────────

    /** Großbuchstaben-Hex ohne führende Nullen (wie Go {@code %X}), so wie KeePassRPC es erwartet. */
    static String toHex(BigInteger value) {
        return value.toString(16).toUpperCase();
    }

    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            text.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return text.toString();
    }

    static byte[] keyBytes(char[] hexKey) throws KeePassRpcException {
        if (hexKey == null || hexKey.length != 64) {
            throw new KeePassRpcException(KeePassRpcException.Kind.AUTH_FAILED,
                    "Pairing-Schlüssel hat nicht das erwartete Format");
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 64; i += 2) {
            int high = Character.digit(hexKey[i], 16);
            int low = Character.digit(hexKey[i + 1], 16);
            if (high < 0 || low < 0) {
                Arrays.fill(out, (byte) 0);
                throw new KeePassRpcException(KeePassRpcException.Kind.AUTH_FAILED,
                        "Pairing-Schlüssel hat nicht das erwartete Format");
            }
            out[i / 2] = (byte) ((high << 4) + low);
        }
        return out;
    }

    static byte[] sha256(byte[] input) {
        return digest("SHA-256").digest(input);
    }

    static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] utf8(char[] value) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
            Arrays.fill(part, (byte) 0);
        }
        return out.toByteArray();
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " nicht verfügbar", e);
        }
    }
}
