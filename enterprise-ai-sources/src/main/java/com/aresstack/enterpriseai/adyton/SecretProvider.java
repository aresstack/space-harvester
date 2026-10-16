package com.aresstack.enterpriseai.adyton;

import com.aresstack.enterpriseai.astu.security.SecretRef;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Neutraler Port zum Auflösen eines {@link SecretRef} in kurzlebiges {@link SecretMaterial}.
 *
 * <p>Implementiert von Security-Adaptern (z. B. {@code security-keepassrpc}), benutzt ausschließlich von
 * Adaptern, die sich bei einem externen System authentifizieren (z. B. {@code source-confluence}).
 * Domain, Application und UI reichen nur den {@link SecretRef} weiter.
 *
 * <p>Vorbild: corenth {@code adyton.SecretMaterialProvider}, ohne dessen Broker-/Lease-/Cache-Schicht, die
 * dieses Projekt (noch) nicht braucht. Implementierungen dürfen interaktiv sein (z. B. Pairing) und blockieren;
 * sie werden nicht auf dem Swing-EDT aufgerufen.
 */
public interface SecretProvider {

    /**
     * Löst die Referenz auf. Der Aufrufer besitzt das Ergebnis und muss es schließen.
     *
     * @throws SecretUnavailableException wenn das Backend nicht erreichbar ist, der Eintrag fehlt, der Zugriff
     *                                    abgelehnt oder vom Benutzer abgebrochen wurde
     */
    SecretMaterial resolve(SecretRef ref) throws SecretUnavailableException;

    /**
     * Löst die Referenz auf, übergibt das Material an {@code use} und löscht es danach in jedem Fall.
     * Bevorzugter Weg für Adapter: das Material verlässt den Aufruf nicht.
     */
    default <T, X extends Exception> T withSecret(SecretRef ref, SecretFunction<T, X> use)
            throws SecretUnavailableException, X {
        if (use == null) {
            throw new IllegalArgumentException("Verwendung darf nicht null sein");
        }
        SecretMaterial material = resolve(ref);
        if (material == null) {
            throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_FOUND, ref,
                    "Secret-Provider lieferte kein Material");
        }
        try {
            return use.apply(material);
        } finally {
            material.close();
        }
    }

    /**
     * Die Einträge, die das Backend zur Auswahl anbietet (Titel und Adresse, nie Secrets). Backends ohne
     * Verzeichnis liefern eine leere Liste.
     *
     * @throws SecretUnavailableException wenn das Backend nicht erreichbar ist oder der Zugriff scheitert
     */
    default List<SecretEntry> entries() throws SecretUnavailableException {
        return Collections.emptyList();
    }

    /**
     * Die im Eintrag zur Referenz hinterlegte Adresse (z. B. die Basis-URL eines Dienstes) oder {@code null},
     * wenn das Backend keine kennt.
     *
     * @throws SecretUnavailableException wenn das Backend nicht erreichbar ist oder der Eintrag fehlt
     */
    default String locationOf(SecretRef ref) throws SecretUnavailableException {
        return null;
    }

    /**
     * Weitere, nicht geheime Felder des Eintrags zur Referenz (Name → Wert), z. B. die von MainframeMate gepflegten
     * {@code MM_CertAlias} und {@code MM_RequiresLogin}; nie Benutzername oder Passwort. Backends ohne solche
     * Felder liefern eine leere Map.
     *
     * @throws SecretUnavailableException wenn das Backend nicht erreichbar ist oder der Eintrag fehlt
     */
    default Map<String, String> attributesOf(SecretRef ref) throws SecretUnavailableException {
        return Collections.emptyMap();
    }

    /**
     * Setzt ein nicht geheimes Zusatzfeld des bestehenden Eintrags zur Referenz (z. B. {@code MM_CertAlias} wie
     * MainframeMate); Benutzer, Passwort, URL und die übrigen Felder bleiben unverändert. Ein leerer Wert leert das
     * Feld. Backends ohne Schreibweg melden {@link SecretUnavailableException.Reason#NOT_AVAILABLE}.
     *
     * @throws SecretUnavailableException wenn das Backend nicht erreichbar ist, nicht schreiben kann oder der Eintrag
     *                                    fehlt
     */
    default void setAttribute(SecretRef ref, String name, String value) throws SecretUnavailableException {
        throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_AVAILABLE, ref,
                "dieser Tresor kann keine Zusatzfelder schreiben");
    }
}
