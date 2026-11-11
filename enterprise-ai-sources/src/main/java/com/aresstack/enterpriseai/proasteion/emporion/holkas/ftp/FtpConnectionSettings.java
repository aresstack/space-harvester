package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import com.aresstack.enterpriseai.astu.security.SecretRef;

import java.nio.charset.Charset;
import java.nio.file.Path;

/** Verbindungs- und Übertragungseinstellungen einer FTP-Quelle (ohne Zugangsdaten, nur deren Verweis). */
final class FtpConnectionSettings {

    /** Satzstruktur: automatisch (MVS = Satzstruktur), immer oder nie. */
    enum RecordStructure { AUTO, ON, OFF }

    /** Transportverschlüsselung: keine (wie bisher), explizites FTPS ({@code AUTH TLS}) oder implizites FTPS. */
    enum Tls { NONE, EXPLICIT, IMPLICIT }

    final String host;
    final int port;
    final SecretRef credentialRef;
    final Charset encoding;
    final int connectTimeoutMillis;
    final int readTimeoutMillis;
    final RecordStructure recordStructure;
    final Tls tls;
    /** PEM- oder DER-Datei mit den vertrauten Zertifikaten; {@code null} = Vertrauensquellen der JVM. */
    final Path trustBundle;

    FtpConnectionSettings(String host, int port, SecretRef credentialRef, Charset encoding, int connectTimeoutMillis,
                          int readTimeoutMillis, RecordStructure recordStructure) {
        this(host, port, credentialRef, encoding, connectTimeoutMillis, readTimeoutMillis, recordStructure, Tls.NONE,
                null);
    }

    FtpConnectionSettings(String host, int port, SecretRef credentialRef, Charset encoding, int connectTimeoutMillis,
                          int readTimeoutMillis, RecordStructure recordStructure, Tls tls, Path trustBundle) {
        if (host == null || host.trim().isEmpty() || encoding == null || recordStructure == null || tls == null) {
            throw new IllegalArgumentException("host, encoding, recordStructure and tls are required");
        }
        this.host = host.trim();
        this.port = port;
        this.credentialRef = credentialRef;
        this.encoding = encoding;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.recordStructure = recordStructure;
        this.tls = tls;
        this.trustBundle = trustBundle;
    }
}
