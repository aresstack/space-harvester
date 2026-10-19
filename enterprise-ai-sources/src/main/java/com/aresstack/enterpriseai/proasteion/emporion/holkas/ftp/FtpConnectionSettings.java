package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import com.aresstack.enterpriseai.astu.security.SecretRef;

import java.nio.charset.Charset;

/** Verbindungs- und Übertragungseinstellungen einer FTP-Quelle (ohne Zugangsdaten, nur deren Verweis). */
final class FtpConnectionSettings {

    /** Satzstruktur: automatisch (MVS = Satzstruktur), immer oder nie. */
    enum RecordStructure { AUTO, ON, OFF }

    final String host;
    final int port;
    final SecretRef credentialRef;
    final Charset encoding;
    final int connectTimeoutMillis;
    final int readTimeoutMillis;
    final RecordStructure recordStructure;

    FtpConnectionSettings(String host, int port, SecretRef credentialRef, Charset encoding, int connectTimeoutMillis,
                          int readTimeoutMillis, RecordStructure recordStructure) {
        if (host == null || host.trim().isEmpty() || encoding == null || recordStructure == null) {
            throw new IllegalArgumentException("host, encoding and recordStructure are required");
        }
        this.host = host.trim();
        this.port = port;
        this.credentialRef = credentialRef;
        this.encoding = encoding;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.recordStructure = recordStructure;
    }
}
