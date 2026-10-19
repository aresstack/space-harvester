package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.tika.DocumentExtraction;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview.BetaViewCredentials;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview.BetaViewCredentialsProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview.BetaViewSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.FtpSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.JesSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.NdvSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.SharePointCredentials;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.SharePointCredentialsProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.SharePointSourceProvider;

/**
 * Quellentypen aus den Kopien des Enterprise AI Client ohne Seitenliste je Partition (#187), über
 * {@link ContextualSourceAdapter} als eine Partition je Quelle: {@code ftp} (MVS-Datensätze und Member), {@code jes}
 * (Spool), {@code ndv} (Natural Development Server), {@code sharepoint} (Dokumentbibliotheken) und {@code betaview}
 * (Listen und Reports eines Favoriten). Die Einstellungen
 * stehen unter {@code options} mit den Schlüsseln des Upstream-Typs.
 *
 * <p>{@code options.credentialRef} löst der {@code SecretProvider} des Katalogs auf, also der Anbieter des einbettenden
 * Programms (#188, {@code Harvester.builder().secretProvider}); ohne ihn gibt es keine Zugangsdaten, und {@code jes},
 * {@code ndv} und {@code betaview}, die einen Eintrag verlangen, scheitern beim ersten Zugriff mit {@code ACCESS_DENIED}. {@code auth},
 * {@code base-url} und eigene Zertifikate kennt dieser Weg nicht. SharePoint und BetaView gehen über die Proxy-Route je Ziel; ohne
 * Eintrag meldet es sich mit der Windows-Anmeldung des JDK an, wie im Enterprise AI Client.
 */
public final class EacSourceAdapters {

    /** Für die Prüfung: {@code validate} löst keine Zugangsdaten auf. */
    private static final SecretProvider NO_SECRETS = ref -> {
        throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_AVAILABLE, ref,
                "keine Zugangsdaten bei der Prüfung");
    };

    private EacSourceAdapters() {
    }

    public static SourceAdapter ftp() {
        return new ContextualSourceAdapter(FtpSourceProvider.TYPE_ID, "FTP", false,
                context -> new FtpSourceProvider(secrets(context)));
    }

    public static SourceAdapter jes() {
        return new ContextualSourceAdapter(JesSourceProvider.TYPE_ID, "JES", true,
                context -> new JesSourceProvider(secrets(context)));
    }

    public static SourceAdapter ndv() {
        return new ContextualSourceAdapter(NdvSourceProvider.TYPE_ID, "NDV", true,
                context -> new NdvSourceProvider(secrets(context)));
    }

    public static SourceAdapter sharePoint() {
        return new ContextualSourceAdapter(SharePointSourceProvider.TYPE_ID, "SharePoint", false, context -> {
            SecretProvider secrets = secrets(context);
            return new SharePointSourceProvider(ref -> sharePointCredentials(secrets, ref),
                    context == null ? null : context.routes(), null, context == null ? null : context.userAgent(),
                    DocumentExtraction.detector(), DocumentExtraction.registry());
        });
    }

    public static SourceAdapter betaView() {
        return new ContextualSourceAdapter(BetaViewSourceProvider.TYPE_ID, "BetaView", true, context -> {
            SecretProvider secrets = secrets(context);
            return new BetaViewSourceProvider(ref -> betaViewCredentials(secrets, ref),
                    context == null ? null : context.routes(), null, context == null ? null : context.userAgent(),
                    DocumentExtraction.detector(), DocumentExtraction.registry());
        });
    }

    private static SecretProvider secrets(SourceAdapter.Context context) {
        return context == null || context.secrets() == null ? NO_SECRETS : context.secrets();
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
