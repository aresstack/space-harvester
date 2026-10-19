package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SecretProvider} für Zugangsdaten aus der Konfiguration (YAML, Umgebungsvariablen). Jede Quelle mit
 * Anmeldung bekommt die Referenz {@code source:<id>}: Basic als Benutzername und Passwort, ein Personal Access Token
 * ohne Benutzername (der Confluence-Adapter sendet es dann als {@code Bearer}). Das Geheimnis wird bei jeder
 * Auflösung als frische Kopie herausgegeben, die der Adapter nach Gebrauch löscht. Azure Key Vault (#22) braucht keinen
 * eigenen {@link SecretProvider}: Spring löst Platzhalter über die optionale Key-Vault-PropertySource auf, bevor die
 * Werte hier ankommen.
 */
final class ConfiguredSecrets implements SecretProvider {

    private final Map<SecretRef, Credentials> credentials = new ConcurrentHashMap<>();
    private final SourceSecrets fallback;

    ConfiguredSecrets() {
        this(null);
    }

    /**
     * @param fallback Anbieter des einbettenden Programms (#188, etwa KeePassRPC oder ein Tresor), gefragt für jede
     *                 Referenz ohne Zugangsdaten aus der Konfiguration; {@code null} ohne
     */
    ConfiguredSecrets(SourceSecrets fallback) {
        this.fallback = fallback;
    }

    /** Ob Zugangsdaten, die in der Konfiguration fehlen, vom Anbieter des einbettenden Programms kommen. */
    boolean external() {
        return fallback != null;
    }

    /** Präfix der Verweise auf Zugangsdaten aus der Konfiguration; nie in {@code options} einer Quelle. */
    static final String SOURCE_PREFIX = "source:";

    static SecretRef refFor(String sourceId) {
        return SecretRef.of(SOURCE_PREFIX + sourceId);
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

    /** Zugangsdaten einer Quelle zum späteren {@link #restore}, etwa wenn ihr Ersatz nicht öffnet (#188). */
    Saved save(String sourceId) {
        Map<SecretRef, Credentials> saved = new HashMap<>();
        for (SecretRef ref : List.of(refFor(sourceId), certificateRefFor(sourceId))) {
            Credentials found = credentials.get(ref);
            if (found != null) {
                saved.put(ref, found);
            }
        }
        return new Saved(sourceId, Map.copyOf(saved));
    }

    void restore(Saved saved) {
        forget(saved.sourceId);
        credentials.putAll(saved.credentials);
    }

    /** Vergisst die Zugangsdaten einer Quelle, die entfernt oder ersetzt wird. */
    void forget(String sourceId) {
        credentials.remove(refFor(sourceId));
        credentials.remove(certificateRefFor(sourceId));
    }

    /** Gesicherte Zugangsdaten; das {@code toString} zeigt keine. */
    static final class Saved {

        private final String sourceId;
        private final Map<SecretRef, Credentials> credentials;

        private Saved(String sourceId, Map<SecretRef, Credentials> credentials) {
            this.sourceId = sourceId;
            this.credentials = credentials;
        }

        @Override
        public String toString() {
            return "Saved[sourceId=" + sourceId + ", entries=" + credentials.size() + "]";
        }
    }

    /** Ein frischer Satz mit demselben Anbieter, etwa für die Prüfung einer Quelle vor dem Speichern. */
    ConfiguredSecrets fresh() {
        return new ConfiguredSecrets(fallback);
    }

    @Override
    public SecretMaterial resolve(SecretRef ref) throws SecretUnavailableException {
        Credentials found = credentials.get(ref);
        if (found == null && fallback != null) {
            return external(ref);
        }
        if (found == null) {
            throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_FOUND, ref,
                    "keine Zugangsdaten konfiguriert");
        }
        return new SecretMaterial(ref, found.principal(), found.secret().toCharArray());
    }

    private SecretMaterial external(SecretRef ref) throws SecretUnavailableException {
        Optional<SourceSecrets.Credential> credential;
        try {
            credential = fallback.resolve(ref.id());
        } catch (SourceSecrets.Unavailable e) {
            throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_AVAILABLE, ref,
                    "Anbieter der Zugangsdaten nicht bereit");
        }
        if (credential == null || credential.isEmpty()) {
            throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_FOUND, ref,
                    "keine Zugangsdaten beim Anbieter");
        }
        char[] secret = credential.get().secret();
        try {
            return new SecretMaterial(ref, credential.get().principal(), secret.clone());
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private record Credentials(String principal, String secret) {

        @Override
        public String toString() {
            return "Credentials[principal=" + principal + ", secret=***]";
        }
    }
}
