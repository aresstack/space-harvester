package com.aresstack.enterpriseai.adyton;

import com.aresstack.enterpriseai.astu.security.SecretRef;

import java.util.Arrays;

/**
 * Kurzlebiges, aufgelöstes Secret-Material (Principal und Geheimnis) zu einem {@link SecretRef}.
 *
 * <p>Nur der Security-Adapter erzeugt es und nur der konsumierende Adapter (z. B. Confluence) liest es, und
 * zwar unmittelbar vor der Authentifizierung. Danach wird es mit {@link #close()} gelöscht; am einfachsten über
 * {@link SecretProvider#withSecret(SecretRef, SecretFunction)}. Es wird nie gespeichert, geloggt, serialisiert
 * oder an Domain, Application, UI oder Knowledge-Module weitergereicht (geprüft in {@code architecture-tests}).
 *
 * <p>Schutzmaßnahmen: {@code char[]} statt {@code String} für das Geheimnis, Kopien bei Ein- und Ausgabe,
 * Überschreiben beim Schließen, {@link #toString()} ohne Inhalt, nicht {@code Serializable}, Gleichheit nur
 * über Identität (kein inhaltsabhängiger {@code hashCode}). Übernommen aus corenth
 * {@code adyton.SecretMaterial}/{@code DefaultSecretMaterial}, hier als finale Klasse statt Interface.
 */
public final class SecretMaterial implements AutoCloseable {

    private final SecretRef ref;
    private final String principal;
    private final char[] secret;
    private volatile boolean closed;

    /**
     * @param ref       die aufgelöste Referenz
     * @param principal Benutzername/Identität; leer, wenn das Secret keinen Principal hat (z. B. API-Token)
     * @param secret    das Geheimnis; wird kopiert, der Aufrufer sollte sein Array danach selbst löschen
     */
    public SecretMaterial(SecretRef ref, String principal, char[] secret) {
        if (ref == null) {
            throw new IllegalArgumentException("SecretRef darf nicht null sein");
        }
        if (secret == null) {
            throw new IllegalArgumentException("Secret darf nicht null sein");
        }
        this.ref = ref;
        this.principal = principal == null ? "" : principal;
        this.secret = Arrays.copyOf(secret, secret.length);
    }

    /** Die Referenz, zu der dieses Material gehört (loggbar). */
    public SecretRef ref() {
        return ref;
    }

    /** Der Principal (z. B. Benutzername), nie {@code null}; leer, wenn keiner vorhanden ist. */
    public String principal() {
        ensureOpen();
        return principal;
    }

    /** {@code true}, wenn ein nicht leerer Principal vorhanden ist. */
    public boolean hasPrincipal() {
        ensureOpen();
        return !principal.isEmpty();
    }

    /**
     * Eine Kopie des Geheimnisses. Der Aufrufer muss die Kopie nach Gebrauch selbst überschreiben
     * ({@code Arrays.fill(copy, '\0')}).
     *
     * @throws IllegalStateException nach {@link #close()}
     */
    public char[] copySecret() {
        ensureOpen();
        return Arrays.copyOf(secret, secret.length);
    }

    /** {@code true}, sobald das Material gelöscht wurde. */
    public boolean isClosed() {
        return closed;
    }

    /** Überschreibt das Geheimnis. Idempotent. */
    @Override
    public void close() {
        synchronized (secret) {
            Arrays.fill(secret, '\0');
            closed = true;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("SecretMaterial für " + ref + " wurde bereits gelöscht");
        }
    }

    /** Enthält nur die Referenz, nie Principal oder Geheimnis. */
    @Override
    public String toString() {
        return "SecretMaterial[" + ref + ", ***]";
    }
}
