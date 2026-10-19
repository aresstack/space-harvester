package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.proasteion.emporion.deigma.tika.DocumentExtraction;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.git.GitSourceProvider;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * ({@link DocumentExtraction}). Anmeldung ({@code options.credentialRef}, {@code auth}) und eigene Zertifikate gibt es
 * auf diesem Weg noch nicht (#188); bis dahin nur öffentliche und lokale Repositorys.
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
        List<String> problems = new ArrayList<>(adapter(null).check(config));
        if (config.options().containsKey("credentialRef")) {
            problems.add("options.credentialRef wird für type git noch nicht unterstützt");
        }
        return problems;
    }

    @Override
    public OpenedSource open(SourceSettingsConfig config, Context context) {
        if (config.options().containsKey("credentialRef")) {
            throw new InvalidSourceConfigurationException(
                    List.of("options.credentialRef wird für type git noch nicht unterstützt"));
        }
        return adapter(context.routes()).open(config, context);
    }

    private KnowledgeSourceProviderAdapter adapter(HttpRoutePort routes) {
        return new KnowledgeSourceProviderAdapter(new GitSourceProvider(cloneDirectory, null, routes, null,
                DocumentExtraction.detector(), DocumentExtraction.registry()));
    }
}
