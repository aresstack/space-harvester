package com.aresstack.spaceharvester.source.network;

import java.nio.file.Path;

/**
 * Welchen Serverzertifikaten der Core vertraut, wenn eine Quelle oder der KI-Dienst kein eigenes {@code trust-bundle}
 * nennt (#189), wie {@code network.tls.*} im Enterprise AI Client: drei getrennt schaltbare Quellen aus
 * {@code win-trust-java} und optional eine PEM-/DER-Datei mit weiteren CA-Zertifikaten. Ein Zertifikat gilt, sobald
 * eine Quelle es akzeptiert.
 *
 * @param useJvmDefault       JVM-Truststore ({@code cacerts})
 * @param useWindowsRoot      Windows-Stammzertifikate ({@code Windows-ROOT}); außerhalb von Windows ohne Wirkung
 * @param useWindowsCaStores  Windows-Root- und Zwischenzertifikate (Export der Speicher Root und CA); nur Windows
 * @param caCertificatesFile  PEM- oder DER-Datei mit weiteren CA-Zertifikaten, z. B. des Firmen-Proxys; {@code null}
 *                            ohne
 */
public record TrustSettings(boolean useJvmDefault, boolean useWindowsRoot, boolean useWindowsCaStores,
                            Path caCertificatesFile) {

    /** Nur der JVM-Truststore: das Verhalten ohne Einstellung. */
    public static TrustSettings jvm() {
        return new TrustSettings(true, false, false, null);
    }

    /** JVM-Truststore und die Windows-Speicher (Root und CA), wie die Vorgabe des Enterprise AI Client unter Windows. */
    public static TrustSettings system() {
        return new TrustSettings(true, true, true, null);
    }

    /** Ob nur der JVM-Truststore gilt; dann bleibt das Vertrauen der JVM unverändert. */
    public boolean jvmOnly() {
        return useJvmDefault && !useWindowsRoot && !useWindowsCaStores && caCertificatesFile == null;
    }

    /** Die Quellen als Text für Protokoll und Dialog, ohne etwas zu laden. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        append(text, useJvmDefault, TrustPolicy.JVM_SOURCE);
        append(text, useWindowsRoot, TrustPolicy.WINDOWS_ROOT_SOURCE);
        append(text, useWindowsCaStores, TrustPolicy.WINDOWS_CA_SOURCE);
        append(text, caCertificatesFile != null, TrustPolicy.FILE_SOURCE + " " + (caCertificatesFile == null ? ""
                : caCertificatesFile.getFileName()));
        return text.isEmpty() ? "keine" : text.toString();
    }

    private static void append(StringBuilder text, boolean on, String source) {
        if (on) {
            text.append(text.isEmpty() ? "" : ", ").append(source);
        }
    }
}
