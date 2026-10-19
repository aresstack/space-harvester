package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;

import java.io.IOException;
import java.util.Arrays;

/**
 * Eine wiederverwendete FTP-Sitzung je Quelle. Die Zugangsdaten werden nur beim (Wieder-)Verbinden über den
 * {@link SecretProvider} aufgelöst und danach vergessen; bricht die Verbindung ab (Leerlauf-Timeout des Servers),
 * wird einmal neu verbunden. Vorgänge laufen nacheinander.
 */
final class FtpSessionPool<S extends FtpSessionPool.Session> {

    /** Eine angemeldete Sitzung (Dateien oder JES). */
    interface Session {
        /** Trennt die Verbindung; mehrfacher Aufruf ist erlaubt. */
        void close();
    }

    /** Ein Vorgang auf der angemeldeten Sitzung. */
    interface Operation<S, T> {
        T run(S session) throws IOException, KnowledgeSourceException;
    }

    /** Öffnet eine angemeldete Sitzung (Naht für Tests). */
    interface Connector<S> {
        S open(FtpConnectionSettings settings, String user, char[] password) throws IOException;
    }

    private static final String ANONYMOUS = "anonymous";

    private final SourceAddress sourceId;
    private final FtpConnectionSettings settings;
    private final SecretProvider secrets;
    private final Connector<S> connector;
    private S session;

    FtpSessionPool(SourceAddress sourceId, FtpConnectionSettings settings, SecretProvider secrets,
                   Connector<S> connector) {
        if (sourceId == null || settings == null || connector == null) {
            throw new IllegalArgumentException("sourceId, settings and connector are required");
        }
        if (settings.credentialRef != null && secrets == null) {
            throw new IllegalArgumentException("credentialRef gesetzt, aber kein SecretProvider");
        }
        this.sourceId = sourceId;
        this.settings = settings;
        this.secrets = secrets;
        this.connector = connector;
    }

    synchronized <T> T run(Operation<S, T> operation) throws KnowledgeSourceException {
        for (int attempt = 0; ; attempt++) {
            if (session == null) {
                session = connect();
            }
            try {
                return operation.run(session);
            } catch (CommonsNetFtpSession.FtpNotFoundException e) {
                throw new KnowledgeSourceException(Kind.NOT_FOUND, e.getMessage(), e);
            } catch (IOException e) {
                closeSession();
                if (attempt > 0) {
                    throw new KnowledgeSourceException(Kind.UNAVAILABLE,
                            "FTP-Quelle " + sourceId + " (" + settings.host + "): " + e.getMessage(), e);
                }
            }
        }
    }

    /** Trennt die gehaltene Verbindung. */
    synchronized void close() {
        closeSession();
    }

    private void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private S connect() throws KnowledgeSourceException {
        final SecretRef ref = settings.credentialRef;
        try {
            if (ref == null) {
                return connector.open(settings, ANONYMOUS, ANONYMOUS.toCharArray());
            }
            return secrets.withSecret(ref, (SecretMaterial material) -> {
                char[] password = material.copySecret();
                try {
                    return connector.open(settings, material.principal(), password);
                } finally {
                    Arrays.fill(password, '\0');
                }
            });
        } catch (SecretUnavailableException e) {
            Kind kind = e.reason() == SecretUnavailableException.Reason.NOT_AVAILABLE ? Kind.UNAVAILABLE : Kind.ACCESS_DENIED;
            throw new KnowledgeSourceException(kind,
                    "Anmeldedaten für FTP-Quelle " + sourceId + " nicht verfügbar (" + e.reason() + ", " + ref + ")");
        } catch (CommonsNetFtpSession.FtpLoginException e) {
            throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "FTP-Quelle " + sourceId + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE,
                    "FTP-Quelle " + sourceId + " (" + settings.host + ") nicht erreichbar: " + e.getMessage(), e);
        }
    }
}
