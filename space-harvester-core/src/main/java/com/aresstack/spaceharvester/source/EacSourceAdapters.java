package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.tika.DocumentExtraction;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview.BetaViewCredentials;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview.BetaViewCredentialsProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview.BetaViewSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.FtpSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.JesSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.jira.JiraSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.NdvSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.outlook.OutlookSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.SharePointCredentials;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.SharePointCredentialsProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.SharePointSourceProvider;
import com.aresstack.spaceharvester.application.sources.SourceDefaults;

/**
 * Quellentypen aus den Kopien des Enterprise AI Client ohne Seitenliste je Partition (#187), über
 * {@link KnowledgeSourceProviderAdapter} als eine Partition je Quelle: {@code ftp} (MVS-Datensätze und Member), {@code jes}
 * (Spool), {@code ndv} (Natural Development Server), {@code sharepoint} (Dokumentbibliotheken) und {@code betaview}
 * (Listen und Reports eines Favoriten) sowie {@code outlook} (lokale Postfachdateien .pst/.ost unter
 * {@code options.mailbox}, ohne Zugangsdaten) und {@code jira} (Vorgänge von Jira Data Center, eine Partition je
 * Projekt, {@link #jira()}). Die Einstellungen stehen unter {@code options} mit den Schlüsseln des
 * Upstream-Typs, der Titel unter {@code title}.
 *
 * <p>Anmeldung (#188, ADR 0022) wie bei MediaWiki: {@code auth.type basic} mit Benutzer und Passwort setzt
 * {@code credentialRef} auf {@code source:<id>}; fehlt das Passwort, fragt der {@code SecretProvider} des Katalogs den
 * Anbieter des einbettenden Programms ({@code Harvester.builder().secretProvider}). Ein eigener Verweis unter
 * {@code options.credentialRef} (etwa {@code keepass:jes}) geht bei {@code auth.type none} unverändert an diesen
 * Anbieter. {@code jes}, {@code ndv} und {@code betaview} verlangen eines von beiden. SharePoint und BetaView gehen über
 * die Proxy-Route je Ziel und nehmen {@code trust-bundle} und {@code client-certificate} (mTLS); ohne Eintrag meldet
 * sich SharePoint mit der Windows-Anmeldung des JDK an, wie im Enterprise AI Client. FTP, JES und NDV kennen im
 * Upstream kein TLS; dort sind beide Angaben ein Fehler. {@code base-url} gibt es auf diesem Weg nicht.
 */
public final class EacSourceAdapters {

    private static final String CREDENTIAL_REF = "credentialRef";

    private EacSourceAdapters() {
    }

    public static SourceAdapter ftp() {
        return KnowledgeSourceProviderAdapter.connected(connection -> new FtpSourceProvider(connection.secrets()),
                CREDENTIAL_REF, null).named("FTP").withoutTls().withCredentialRefPassThrough()
                .withDefaults(SourceDefaults.TARGETED_SEARCH);
    }

    public static SourceAdapter jes() {
        return KnowledgeSourceProviderAdapter.connected(connection -> new JesSourceProvider(connection.secrets()),
                CREDENTIAL_REF, null).named("JES").withoutTls().withCredentialRefPassThrough().requiringCredentials()
                .withDefaults(SourceDefaults.TARGETED_SEARCH);
    }

    public static SourceAdapter ndv() {
        return KnowledgeSourceProviderAdapter.connected(connection -> new NdvSourceProvider(connection.secrets()),
                CREDENTIAL_REF, null).named("NDV").withoutTls().withCredentialRefPassThrough().requiringCredentials()
                .withDefaults(SourceDefaults.TARGETED_SEARCH);
    }

    public static KnowledgeSourceProviderAdapter sharePoint() {
        return KnowledgeSourceProviderAdapter.connected(connection -> new SharePointSourceProvider(
                ref -> sharePointCredentials(connection.secrets(), ref), connection.routes(), connection.tls(),
                connection.userAgent(), new OneNoteContentDetector(DocumentExtraction.detector()),
                DocumentExtraction.registry()),
                CREDENTIAL_REF, null).withCredentialRefPassThrough().withDefaults(SourceDefaults.INTEGRATED_LOGIN);
    }

    public static SourceAdapter betaView() {
        return KnowledgeSourceProviderAdapter.connected(connection -> new BetaViewSourceProvider(
                ref -> betaViewCredentials(connection.secrets(), ref), connection.routes(), connection.tls(),
                connection.userAgent(), new OneNoteContentDetector(DocumentExtraction.detector()),
                DocumentExtraction.registry()),
                CREDENTIAL_REF, null).withCredentialRefPassThrough().requiringCredentials()
                .withDefaults(SourceDefaults.TARGETED_SEARCH);
    }

    /**
     * Jira Data Center (REST-API 2, nur lesend) wie im Enterprise AI Client ({@code AdapterAssembly.sourceProviders}):
     * Projekte sind die Partitionen, Vorgänge die Seiten, die Lese-Ansicht kommt aus {@code PageViewSource}. Adresse
     * {@code options.baseUrl}, Einschränkung {@code options.jql}. Anmeldung {@code basic} (Benutzer und Passwort) oder
     * {@code pat} (Personal Access Token, als Bearer); ein eigener KeePass-Verweis unter {@code options.credentialRef}
     * geht wie bei SharePoint an den Anbieter des Programms. Das Client-Zertifikat kommt aus {@code client-certificate}
     * (siehe {@link KnowledgeSourceProviderAdapter}); das eigene Feld des Typs bleibt deshalb ungenutzt. Abgelegt wird
     * der Lesetext samt aller Kommentare, ohne Startpunkte gilt die Liste je Projekt ({@code JiraSnapshots}).
     */
    public static SourceAdapter jira() {
        return KnowledgeSourceProviderAdapter.connected(connection -> new JiraSourceProvider(connection.secrets(),
                connection.routes(), connection.tls(), connection.userAgent(), (alias, keyStoreFile, passwordRef) -> {
                    throw new IllegalArgumentException("Client-Zertifikat für Jira unter client-certificate angeben");
                }), CREDENTIAL_REF, null).withCredentialRefPassThrough().acceptingToken()
                .whenOpened(JiraSnapshots::adapt);
    }

    public static SourceAdapter outlook() {
        return new ContextualSourceAdapter(OutlookSourceProvider.TYPE_ID, "Outlook", false,
                context -> new OutlookSourceProvider());
    }

    /** Wie die Brücke im Enterprise AI Client: bei jeder Anmeldung frisch aufgelöst, das Material sofort gelöscht. */
    private static SharePointCredentialsProvider sharePointCredentials(SecretProvider secrets, SecretRef ref) {
        return () -> {
            try (SecretMaterial material = secrets.resolve(ref)) {
                return new SharePointCredentials(material.principal(), material.copySecret());
            }
        };
    }

    private static BetaViewCredentialsProvider betaViewCredentials(SecretProvider secrets, SecretRef ref) {
        return () -> {
            try (SecretMaterial material = secrets.resolve(ref)) {
                return new BetaViewCredentials(material.principal(), material.copySecret());
            }
        };
    }
}
