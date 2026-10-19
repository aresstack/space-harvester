package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.tika.DocumentExtraction;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.git.GitCredentials;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.git.GitCredentialsProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.git.GitSourceProvider;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Quellentyp {@code git}: ein Branch eines Git-Repositorys über {@code GitSourceProvider} aus
 * {@code enterprise-ai-sources} (#187), nur lesend, als eine Partition je Quelle über {@code discover} und {@code load}
 * ({@link KnowledgeSourceProviderAdapter}). Die Einstellungen stehen unter {@code options} mit den Schlüsseln des
 * Upstream-Typs ({@code url} Pflicht, {@code ref}, {@code startPoints}, {@code include}, {@code exclude},
 * {@code maxFileMegabytes}, {@code maxResources}, {@code maxDepth}).
 *
 * <p>Die bloßen Kopien der Repositorys liegen unter {@code cloneDirectory}. HTTPS geht über dieselbe Proxy-Route je Ziel
 * wie die übrigen Quellen; PDF- und Office-Dateien im Repository liest dieselbe Extraktion wie bei lokalen Dateien
 * ({@link DocumentExtraction}).
 *
 * <p>Anmeldung über {@code options.credentialRef} wie im Enterprise AI Client ({@code SecretBackedGitCredentialsProvider},
 * #188): Benutzer und Lese-Token werden erst beim Abruf über den {@code SecretProvider} des Katalogs aufgelöst
 * (Konfiguration, sonst der Anbieter des einbettenden Programms), nie gespeichert oder protokolliert; der Adapter löscht
 * sie nach dem Abruf. Ein Eintrag ohne Benutzer ist erlaubt (Azure Repos, GitHub werten nur das Token aus). Eigene
 * Server-CAs kommen aus dem {@code trust-bundle} der Quelle, sonst gilt das Standardvertrauen des Programms.
 */
public final class GitSourceAdapter implements SourceAdapter {

    public static final String TYPE = GitSourceProvider.TYPE_ID;

    private final Path cloneDirectory;

    /** @param cloneDirectory Ordner der Anwendung für die bloßen Kopien der Repositorys */
    public GitSourceAdapter(Path cloneDirectory) {
        this.cloneDirectory = Objects.requireNonNull(cloneDirectory, "cloneDirectory");
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<String> check(SourceSettingsConfig config) {
        return adapter(null, null, null).check(config);
    }

    @Override
    public OpenedSource open(SourceSettingsConfig config, Context context) {
        X509TrustManager trust;
        try {
            trust = config.trustBundle() == null ? context.trustBundles().defaultTrust()
                    : context.trustBundles().trustManager(config.trustBundle());
        } catch (IllegalArgumentException e) {
            throw new InvalidSourceConfigurationException(List.of("trust-bundle: " + e.getMessage()));
        }
        return adapter(context.secrets(), context.routes(), socketFactory(trust)).open(config, context);
    }

    private KnowledgeSourceProviderAdapter adapter(SecretProvider secrets, HttpRoutePort routes,
                                                   SSLSocketFactory tls) {
        Function<SecretRef, GitCredentialsProvider> credentials = secrets == null ? null
                : ref -> credentials(secrets, ref);
        return new KnowledgeSourceProviderAdapter(new GitSourceProvider(cloneDirectory, credentials, routes, tls,
                DocumentExtraction.detector(), DocumentExtraction.registry()));
    }

    /** Wie {@code SecretBackedGitCredentialsProvider} im Enterprise AI Client: je Abruf frisch, das Material gelöscht. */
    static GitCredentialsProvider credentials(SecretProvider secrets, SecretRef ref) {
        return () -> {
            try (SecretMaterial material = secrets.resolve(ref)) {
                char[] token = material.copySecret();
                try {
                    if (token.length == 0) {
                        throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_FOUND, ref,
                                "Eintrag hat kein Token");
                    }
                    return new GitCredentials(material.hasPrincipal() ? material.principal() : null, token);
                } finally {
                    Arrays.fill(token, '\0');
                }
            }
        };
    }

    private static SSLSocketFactory socketFactory(X509TrustManager trust) {
        if (trust == null) {
            return null;
        }
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trust}, null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS für Git nicht einrichtbar", e);
        }
    }
}
