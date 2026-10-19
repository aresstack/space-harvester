package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.tika.DocumentExtraction;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.git.GitCredentials;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.git.GitCredentialsProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.git.GitSourceProvider;
import com.aresstack.spaceharvester.application.sources.SourceField;
import com.aresstack.spaceharvester.application.sources.SourceType;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
 * <p>Anmeldung wie im Enterprise AI Client ({@code SecretBackedGitCredentialsProvider}, #188, ADR 0022):
 * {@code auth.type pat} mit {@code token} oder {@code basic} mit Benutzer und Token als Passwort; beides liegt unter
 * {@code source:<id>}, fehlt das Geheimnis, kommt es vom Anbieter des einbettenden Programms. Ein eigener Verweis
 * {@code options.credentialRef} geht bei {@code auth.type none} unverändert an diesen Anbieter. Aufgelöst wird erst
 * beim Abruf, nie gespeichert oder protokolliert; der Adapter löscht das Material danach. Ein Eintrag ohne Benutzer ist
 * erlaubt (Azure Repos, GitHub werten nur das Token aus). TLS je Quelle: Server-CAs aus {@code trust-bundle}, sonst
 * das Standardvertrauen des Programms, Client-Zertifikat aus {@code client-certificate} (mTLS).
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
    public String displayName() {
        return "Git-Repository";
    }

    @Override
    public String addressOption() {
        return adapter().addressOption();
    }

    @Override
    public List<SourceField> fields() {
        return adapter().fields();
    }

    @Override
    public List<SourceType.AuthMode> authModes() {
        return adapter().authModes();
    }

    @Override
    public List<String> check(SourceSettingsConfig config) {
        return adapter().check(config);
    }

    @Override
    public List<String> check(SourceSettingsConfig config, boolean externalSecrets) {
        return adapter().check(config, externalSecrets);
    }

    @Override
    public OpenedSource open(SourceSettingsConfig config, Context context) {
        return adapter().open(config, context);
    }

    private KnowledgeSourceProviderAdapter adapter() {
        return KnowledgeSourceProviderAdapter.connected(connection -> new GitSourceProvider(cloneDirectory,
                        ref -> credentials(connection.secrets(), ref), connection.routes(), connection.tls(),
                        DocumentExtraction.detector(), DocumentExtraction.registry()), "credentialRef", null)
                .named(displayName()).withCredentialRefPassThrough().acceptingToken();
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
}
