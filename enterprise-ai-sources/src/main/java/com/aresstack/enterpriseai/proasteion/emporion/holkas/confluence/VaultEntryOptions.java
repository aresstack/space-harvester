package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Die Zusatzfelder, die MainframeMate im KeePass-Eintrag einer Confluence-Instanz ablegt und dort für den Zugriff
 * nutzt ({@code OpenConfluenceMenuCommand}): {@code MM_CertAlias} (Client-Zertifikat aus {@code Windows-MY}) und
 * {@code MM_RequiresLogin} ({@code false}: ohne Benutzer und Passwort, nur Zertifikat). Einmal je Quelle gelesen;
 * schlägt das Lesen fehl, wird es beim nächsten Zugriff wiederholt. Keine Secrets.
 */
final class VaultEntryOptions {

    static final String CERT_ALIAS = "MM_CertAlias";
    static final String REQUIRES_LOGIN = "MM_RequiresLogin";

    private final SecretProvider secrets;
    private final SecretRef ref;
    private Map<String, String> attributes;

    /** @param ref KeePass-Eintrag der Quelle oder {@code null} (dann keine Zusatzfelder) */
    VaultEntryOptions(SecretProvider secrets, SecretRef ref) {
        this.secrets = secrets;
        this.ref = ref;
    }

    /** Alias des Client-Zertifikats aus dem Eintrag oder {@code null}. */
    String certificateAlias() throws SecretUnavailableException {
        String alias = attributes().get(CERT_ALIAS);
        return alias == null || alias.trim().isEmpty() ? null : alias.trim();
    }

    /** {@code false}, wenn der Eintrag ausdrücklich ohne Anmeldung arbeitet (wie MainframeMate: kein Header). */
    boolean sendsLogin() throws SecretUnavailableException {
        String value = attributes().get(REQUIRES_LOGIN);
        return value == null || !"false".equalsIgnoreCase(value.trim());
    }

    private synchronized Map<String, String> attributes() throws SecretUnavailableException {
        if (attributes == null) {
            Map<String, String> read = ref == null || secrets == null ? null : secrets.attributesOf(ref);
            attributes = read == null ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, String>(read));
        }
        return attributes;
    }

    @Override
    public String toString() {
        return "VaultEntryOptions[" + ref + "]";
    }
}
