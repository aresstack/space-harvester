package com.aresstack.enterpriseai.proasteion.emporion.holkas.git;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.astu.source.KnowledgeSourceType;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRegistry;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSettingsReader;
import com.aresstack.enterpriseai.source.api.files.FilePatterns;

import javax.net.ssl.SSLSocketFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Source type "Git-Repository" ({@code source.<n>.type=git}, resource scheme {@code git}, #137): the current state
 * of exactly one ref, read only. Keys: {@code url} (required; https, http or a local repository), {@code title},
 * {@code ref} (branch, tag or commit id; empty = default branch of the remote), {@code credentialRef} (KeePass entry
 * with user and read token/PAT; empty = anonymous, HTTPS only), {@code startPoints} (path prefixes, default
 * {@code .}), {@code maxDepth} (default 30), {@code maxResources} (default 5000), {@code maxFileMegabytes} (default
 * 2), {@code include}, {@code exclude} (glob patterns; default {@link GitFiles#DEFAULT_EXCLUDES}),
 * {@code timeoutSeconds} (default 120), {@code refreshMinutes} (default 5).
 *
 * <p>JGit stays behind this adapter; the repository cache belongs to the application ({@code cacheDirectory}).
 * HTTP(S) uses the application's proxy route and TLS trust; the composition root fetches the token from KeePass per
 * fetch ({@link GitCredentialsProvider}), and it never ends up in URLs, settings, metadata or messages.
 */
public final class GitSourceProvider implements KnowledgeSourceProvider {

    public static final String TYPE_ID = "git";

    private static final KnowledgeSourceType TYPE = KnowledgeSourceType.builder(TYPE_ID, GitKnowledgeSource.SCHEME,
                    "Git-Repository")
            .description("Ein Branch eines Git-Repositorys (Azure Repos, GitHub, GitLab, lokal), nur lesend: "
                    + "Quelltext, Konfiguration, Dokumentation und Design-Assets")
            .idPrefix("git")
            .fullIndexing(true)
            .summaryKey("url")
            .field(SourceSettingField.text("url", "Repository-URL",
                    "HTTPS-Adresse ohne Zugangsdaten (z. B. dev.azure.com/org/projekt/_git/repo) oder lokaler Pfad").required())
            .field(SourceSettingField.text("title", "Anzeigename (optional)", "Leer = Name aus der Adresse"))
            .field(SourceSettingField.text("ref", "Branch/Tag (optional)",
                    "Branch, Tag oder Commit-ID; leer = Standard-Branch des Repositorys"))
            .field(SourceSettingField.of("credentialRef", SourceSettingField.Kind.SECRET_REF,
                    "KeePass-Eintrag (optional)", "Benutzer und Lese-Token (Azure: PAT mit „Code (Read)“); "
                            + "leer = öffentliches Repository"))
            .field(SourceSettingField.text("startPoints", "Pfade",
                    "Kommagetrennte Ordner oder Dateien im Repository (. = alles)").withDefault(".").advanced())
            .field(SourceSettingField.text("include", "Nur diese Dateien (optional)",
                    "Glob-Muster, kommagetrennt, z. B. src/**, *.md; leer = alle bekannten Dateitypen").advanced())
            .field(SourceSettingField.text("exclude", "Ausschließen",
                    "Glob-Muster, kommagetrennt; Abhängigkeiten, Build-Ausgaben und Lockfiles sind vorbelegt")
                    .withDefault(GitFiles.DEFAULT_EXCLUDES).advanced())
            .field(SourceSettingField.of("maxFileMegabytes", SourceSettingField.Kind.NUMBER,
                    "Größte Datei in MB", "Größere Dateien werden übersprungen; Standard 2").withDefault("2")
                    .advanced())
            .field(SourceSettingField.of("maxResources", SourceSettingField.Kind.NUMBER,
                    "Höchstzahl Dateien (optional)", "Obergrenze je Lauf; leer = 5000").advanced())
            .field(SourceSettingField.of("maxDepth", SourceSettingField.Kind.NUMBER, "Tiefe",
                    "Wie viele Verzeichnisebenen gelesen werden").withDefault("30").advanced())
            .build();

    private static final int DEFAULT_MAX_DEPTH = 30;
    private static final int DEFAULT_MAX_RESOURCES = 5000;

    private final GitRepositoryCache cache;
    private final Function<SecretRef, GitCredentialsProvider> credentials;
    private final HttpRoutePort routes;
    private final SSLSocketFactory tls;
    private final ContentDetector detector;
    private final ExtractionRegistry extraction;

    /** Without vault, proxy route and own trust: local and public repositories (tests, tools). */
    public GitSourceProvider(Path cacheDirectory, ContentDetector detector, ExtractionRegistry extraction) {
        this(cacheDirectory, null, null, null, detector, extraction);
    }

    /**
     * @param cacheDirectory folder owned by the application for the bare copies of the repositories
     * @param credentials    bridge from the KeePass entry of a source to its credentials; {@code null} = none
     * @param routes         proxy route per target URL; {@code null} = JVM default
     * @param tls            trust of the application for HTTPS; {@code null} = JVM default
     * @param detector       content detection for PDF and Office files in repositories
     * @param extraction     extractors for PDF and Office files in repositories
     */
    public GitSourceProvider(Path cacheDirectory, Function<SecretRef, GitCredentialsProvider> credentials,
                             HttpRoutePort routes, SSLSocketFactory tls, ContentDetector detector,
                             ExtractionRegistry extraction) {
        if (cacheDirectory == null || detector == null || extraction == null) {
            throw new IllegalArgumentException("cacheDirectory, detector and extraction must not be null");
        }
        this.cache = new GitRepositoryCache(cacheDirectory);
        this.credentials = credentials;
        this.routes = routes;
        this.tls = tls;
        this.detector = detector;
        this.extraction = extraction;
    }

    /** The type without runtime environment (description, fields). */
    public static KnowledgeSourceType sourceType() {
        return TYPE;
    }

    @Override
    public KnowledgeSourceType type() {
        return TYPE;
    }

    @Override
    public List<String> validate(SourceSettings settings) {
        SourceSettingsReader reader = new SourceSettingsReader(TYPE, settings);
        GitRemote remote = remote(reader);
        ref(reader);
        credentialRef(reader, remote);
        maxFileBytes(reader);
        scope(reader);
        return reader.problems();
    }

    @Override
    public SourceScope scope(SourceSettings settings) {
        return scope(new SourceSettingsReader(TYPE, settings));
    }

    @Override
    public KnowledgeSourcePort open(SourceAddress sourceId, SourceSettings settings) {
        SourceSettingsReader reader = new SourceSettingsReader(TYPE, settings);
        GitRemote remote = remote(reader);
        String ref = ref(reader);
        SecretRef credentialRef = credentialRef(reader, remote);
        long maxFileBytes = maxFileBytes(reader);
        int timeout = reader.integer("timeoutSeconds", 120, 1, 3600);
        long refreshMillis = reader.integer("refreshMinutes", 5, 0, 24 * 60) * 60000L;
        if (reader.hasProblems() || remote == null) {
            throw new IllegalArgumentException("Git-Quelle " + sourceId + " ist fehlerhaft: " + reader.problems());
        }
        FilePatterns patterns = new FilePatterns(FilePatterns.split(reader.text("include", null)),
                FilePatterns.split(reader.text("exclude", GitFiles.DEFAULT_EXCLUDES)));
        return new GitKnowledgeSource(sourceId, remote, ref, locatorOf(remote, ref), reader.text("title", null),
                cache, routes, tls, access(credentialRef), timeout, patterns, maxFileBytes, detector, extraction, refreshMillis,
                System::currentTimeMillis);
    }

    /**
     * Repository without scheme and {@code .git}, plus {@code @<ref>} when a ref is chosen
     * ({@code git:dev.azure.com/org/p/_git/repo@release/1.0/src/A.java}); two sources on different branches of the
     * same repository thus get different ids.
     */
    @Override
    public String locator(SourceSettings settings) {
        SourceSettingsReader reader = new SourceSettingsReader(TYPE, settings);
        GitRemote remote = remote(reader);
        return remote == null ? "" : locatorOf(remote, ref(reader));
    }

    static String locatorOf(GitRemote remote, String ref) {
        return ref.isEmpty() ? remote.locator() : remote.locator() + "@" + ref;
    }

    private static GitRemote remote(SourceSettingsReader r) {
        String url = r.text("url", null);
        try {
            return GitRemote.parse(url);
        } catch (IllegalArgumentException e) {
            r.problem("url", e.getMessage());
            return null;
        }
    }

    private GitCredentialsProvider access(SecretRef credentialRef) {
        if (credentialRef == null) {
            return GitCredentialsProvider.none();
        }
        if (credentials == null) {
            return new GitCredentialsProvider() {
                @Override
                public GitCredentials credentials() {
                    throw new IllegalStateException("no vault");
                }
            };
        }
        return credentials.apply(credentialRef);
    }

    /** A token only travels over HTTPS; local repositories need none. */
    private static SecretRef credentialRef(SourceSettingsReader r, GitRemote remote) {
        SecretRef ref = r.secretRef("credentialRef");
        if (ref != null && remote != null && !remote.uri().toLowerCase(Locale.ROOT).startsWith("https:")) {
            r.problem("credentialRef", "Zugangsdaten nur über HTTPS; bei http:// oder lokalen Repositorys leer lassen");
            return null;
        }
        return ref;
    }

    private static String ref(SourceSettingsReader r) {
        String ref = r.text("ref", "").trim();
        if (ref.contains("..") || ref.contains(" ") || ref.contains("\\") || ref.startsWith("-")
                || ref.endsWith("/") || ref.contains("@{") || ref.contains(":")) {
            r.problem("ref", "kein gültiger Branch-, Tag- oder Commit-Name");
            return "";
        }
        return ref;
    }

    private static long maxFileBytes(SourceSettingsReader r) {
        return r.integer("maxFileMegabytes", 2, 1, 200) * 1024L * 1024L;
    }

    private static SourceScope scope(SourceSettingsReader r) {
        return r.scope(".", DEFAULT_MAX_DEPTH, DEFAULT_MAX_RESOURCES);
    }
}
