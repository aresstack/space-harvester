package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.adyton.SecretMaterial;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * Baut den {@code Authorization}-Header aus {@link SecretMaterial} (paketintern).
 *
 * <ul>
 *   <li>Mit Benutzername: {@code Basic base64(benutzer:passwort)} wie MainframeMate {@code ConfluenceRestClient}.</li>
 *   <li>Ohne Benutzername: {@code Bearer <token>} für Personal Access Tokens von Confluence DC ab 7.9
 *       (UNVERIFIED gegen die Ziel-Instanz; MainframeMate nutzt nur Basic); ohne Token gar kein Header.</li>
 * </ul>
 * Zwischenpuffer werden überschrieben. Der fertige Header ist zwangsläufig ein {@code String}, weil
 * {@code HttpURLConnection} nur solche annimmt; er lebt nur für einen Port-Aufruf.
 */
final class ConfluenceAuthorization {

    private ConfluenceAuthorization() {
    }

    static String header(SecretMaterial material) {
        char[] secret = material.copySecret();
        CharBuffer chars = null;
        ByteBuffer bytes = null;
        byte[] raw = null;
        try {
            if (!material.hasPrincipal()) {
                // Ohne Benutzer und ohne Token kein Header (wie MainframeMate: nur Zertifikat oder anonym).
                return secret.length == 0 ? null : "Bearer " + new String(secret);
            }
            String principal = material.principal();
            chars = CharBuffer.allocate(principal.length() + 1 + secret.length);
            chars.put(principal).put(':').put(secret).flip();
            bytes = StandardCharsets.UTF_8.encode(chars);
            raw = new byte[bytes.remaining()];
            bytes.get(raw);
            return "Basic " + Base64.getEncoder().encodeToString(raw);
        } finally {
            Arrays.fill(secret, '\0');
            if (chars != null) {
                Arrays.fill(chars.array(), '\0');
            }
            if (bytes != null && bytes.hasArray()) {
                Arrays.fill(bytes.array(), (byte) 0);
            }
            if (raw != null) {
                Arrays.fill(raw, (byte) 0);
            }
        }
    }
}
