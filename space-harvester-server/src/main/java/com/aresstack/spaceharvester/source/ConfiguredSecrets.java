package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link SecretProvider} für Zugangsdaten aus der Konfiguration (YAML, Umgebungsvariablen). Jede Quelle mit
 * Anmeldung bekommt die Referenz {@code source:<id>}: Basic als Benutzername und Passwort, ein Personal Access Token
 * ohne Benutzername (der Confluence-Adapter sendet es dann als {@code Bearer}). Das Geheimnis wird bei jeder
 * Auflösung als frische Kopie herausgegeben, die der Adapter nach Gebrauch löscht. Azure Key Vault (#22) braucht keinen
 * eigenen {@link SecretProvider}: Spring löst Platzhalter über die optionale Key-Vault-PropertySource auf, bevor die
 * Werte hier ankommen.
 */
final class ConfiguredSecrets implements SecretProvider {

    private final Map<SecretRef, Credentials> credentials = new HashMap<>();

    static SecretRef refFor(String sourceId) {
        return SecretRef.of("source:" + sourceId);
    }

    /** Passwort der PKCS#12-Datei mit dem Client-Zertifikat einer Quelle; getrennt von der Anmeldung. */
    static SecretRef certificateRefFor(String sourceId) {
        return SecretRef.of("source:" + sourceId + ":client-certificate");
    }

    /**
     * @param principal Benutzername oder {@code null} für ein Token ohne Benutzer
     * @param secret    Passwort oder Token
     */
    void put(String sourceId, String principal, String secret) {
        put(refFor(sourceId), principal, secret);
    }

    void put(SecretRef ref, String principal, String secret) {
        credentials.put(ref, new Credentials(principal, secret));
    }

    @Override
    public SecretMaterial resolve(SecretRef ref) throws SecretUnavailableException {
        Credentials found = credentials.get(ref);
        if (found == null) {
            throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_FOUND, ref,
                    "keine Zugangsdaten konfiguriert");
        }
        return new SecretMaterial(ref, found.principal(), found.secret().toCharArray());
    }

    private record Credentials(String principal, String secret) {

        @Override
        public String toString() {
            return "Credentials[principal=" + principal + ", secret=***]";
        }
    }
}
