package com.aresstack.enterpriseai.proasteion.emporion.holkas.git;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Paritäts-Repository für die Git-Quelle (#193, ADR 0012, docs/PARITY.md, „Vergleichstests“): dasselbe bloße
 * Repository für den Enterprise AI Client und den Core, lokal, ohne Netz und ohne Zugangsdaten.
 *
 * <p>Liegt im Paket von {@code GitSourceProvider} und braucht nur Java 8 und JGit, damit die Datei unverändert in die
 * Test-Fixtures von {@code source-git} im Enterprise AI Client übernommen werden kann. Im Core liegt sie in den
 * Test-Fixtures von {@code space-harvester-core}, weil {@code enterprise-ai-sources} eine unveränderte Kopie bleibt.
 * Autor, Zeitpunkt und Inhalte sind fest, darum hat jeder Commit in beiden Repositories dieselbe ID.</p>
 *
 * <p>Inhalt von {@link #BRANCH}:</p>
 * <ul>
 *   <li>{@code README.md} im Wurzelordner und Markdown bis Ebene 4: {@code docs/architektur.md},
 *       {@code docs/betrieb/handbuch.md}, {@code docs/betrieb/nacht/ablauf.md}.</li>
 *   <li>Überschriften, Tabelle 3 × 3 mit Kopfzeile, verschachtelte und nummerierte Listen, Codeblock, Links (extern,
 *       relativ auf eine andere Datei, {@code mailto:}).</li>
 *   <li>Quelltext ({@code src/Main.java}) und reiner Text ({@code notizen.txt}).</li>
 *   <li>Ein Bild ({@link #IMAGE}), das kein Textdokument ist.</li>
 *   <li>Zwei Commits: {@link #FIRST_COMMIT_FILES} mit dem alten Stand von {@code docs/betrieb/handbuch.md} und
 *       Tag {@link #TAG}, danach der aktuelle Stand mit {@link #FILES}.</li>
 * </ul>
 */
public final class ParityGit {

    public static final String BRANCH = "main";
    /** Leichtgewichtiger Tag auf dem ersten Commit. */
    public static final String TAG = "v1.0";

    /** Textdokumente des aktuellen Stands in Pfadreihenfolge. */
    public static final String[] DOCUMENTS = {"README.md", "docs/architektur.md", "docs/betrieb/handbuch.md",
            "docs/betrieb/nacht/ablauf.md", "notizen.txt", "src/Main.java"};
    /** Das Bild ohne Text; die Quelle führt es als Ressource neben den Textdokumenten. */
    public static final String IMAGE = "docs/bilder/logo.png";
    /** Tiefste Ordnerebene unter dem Wurzelordner ({@code docs/betrieb/nacht} = 3, die Datei darin auf Ebene 4). */
    public static final int DEPTH = 4;

    /** Sätze, die in jedem Export und jeder Leseansicht vorkommen müssen; je Textdokument mindestens einer. */
    public static final String[] SENTENCES = {
            "Satz Alpha im Readme.",
            "Satz Bravo zur Architektur.",
            "Satz Charlie zum Betrieb.",
            "Satz Delta zum Nachtlauf.",
            "Satz Echo in den Notizen.",
            "Satz Foxtrot im Quelltext.",
    };

    /** Satz nur im alten Stand von {@code docs/betrieb/handbuch.md} (erster Commit, Tag {@link #TAG}). */
    public static final String OLD_SENTENCE = "Satz Golf aus der ersten Fassung.";

    /** Suchbegriff und das Dokument, das ihn als einziges enthält. */
    public static final String SEARCH = "Nachtlauf";
    public static final String SEARCH_HIT = "docs/betrieb/nacht/ablauf.md";

    /** Bild ohne Text: ein PNG mit 1 × 1 Pixel. */
    public static final byte[] LOGO_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    /** Inhalte des aktuellen Stands je Pfad ({@code String} als UTF-8, sonst Bytes). */
    public static final Map<String, Object> FILES;
    /** Inhalte des ersten Commits je Pfad. */
    public static final Map<String, Object> FIRST_COMMIT_FILES;

    private static final long TIME = 1792281600000L; // 2026-10-18T00:00:00Z
    private static final PersonIdent AUTHOR = new PersonIdent("Parität", "paritaet@example.invalid",
            new Date(TIME), TimeZone.getTimeZone("UTC"));

    static {
        Map<String, Object> files = new LinkedHashMap<String, Object>();
        files.put("README.md", "# Handbuch\n\nSatz Alpha im Readme.\n\n"
                + "Siehe [Architektur](docs/architektur.md) und [Dokumentation](https://example.org/doku), "
                + "Fragen an [Team](mailto:team@example.org).\n");
        files.put("docs/architektur.md", "# Architektur\n\nSatz Bravo zur Architektur.\n\n"
                + "## Komponenten\n\n"
                + "| Name | Rolle | Ort |\n| --- | --- | --- |\n| Core | Bibliothek | JVM |\n"
                + "| Server | REST | Dienst |\n| App | Oberfläche | Desktop |\n\n"
                + "- Quellen\n  - Confluence\n  - Git\n- Export\n\n"
                + "1. Beschaffen\n2. Konvertieren\n\n"
                + "```java\nSystem.out.println(\"Parität\");\n```\n");
        files.put("docs/betrieb/handbuch.md", "# Betrieb\n\nSatz Charlie zum Betrieb.\n\n"
                + "Weiter mit dem [Ablauf](nacht/ablauf.md).\n");
        files.put("docs/betrieb/nacht/ablauf.md", "# Ablauf\n\nSatz Delta zum Nachtlauf.\n");
        files.put(IMAGE, LOGO_PNG);
        files.put("notizen.txt", "Satz Echo in den Notizen.\n");
        files.put("src/Main.java", "/** Satz Foxtrot im Quelltext. */\npublic class Main {\n"
                + "    public static void main(String[] args) {\n    }\n}\n");
        FILES = java.util.Collections.unmodifiableMap(files);

        Map<String, Object> first = new LinkedHashMap<String, Object>(files);
        first.put("docs/betrieb/handbuch.md", "# Betrieb\n\nSatz Golf aus der ersten Fassung.\n");
        FIRST_COMMIT_FILES = java.util.Collections.unmodifiableMap(first);
    }

    private ParityGit() {
    }

    /**
     * Legt das bloße Repository in {@code directory} an (der Ordner darf nicht existieren oder muss leer sein) und gibt
     * die ID des aktuellen Commits auf {@link #BRANCH} zurück; {@code url} der Quelle ist der Pfad des Ordners.
     */
    public static String create(Path directory) {
        Repository repository = null;
        try {
            repository = new FileRepositoryBuilder().setGitDir(directory.toFile()).setBare().build();
            repository.create(true);
            String first = commit(repository, null, FIRST_COMMIT_FILES, "Erste Fassung");
            ref(repository, Constants.R_TAGS + TAG, first);
            String current = commit(repository, first, FILES, "Aktueller Stand");
            ref(repository, Constants.R_HEADS + BRANCH, current);
            repository.updateRef(Constants.HEAD).link(Constants.R_HEADS + BRANCH);
            return current;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    private static String commit(Repository repository, String parent, Map<String, Object> files, String message)
            throws IOException {
        ObjectInserter inserter = repository.newObjectInserter();
        try {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            for (Map.Entry<String, Object> file : new TreeMap<String, Object>(files).entrySet()) {
                Object value = file.getValue();
                byte[] bytes = value instanceof byte[] ? (byte[]) value
                        : String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                DirCacheEntry entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, bytes));
                builder.add(entry);
            }
            builder.finish();
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(index.writeTree(inserter));
            commit.setAuthor(AUTHOR);
            commit.setCommitter(AUTHOR);
            commit.setMessage(message);
            if (parent != null) {
                commit.setParentId(ObjectId.fromString(parent));
            }
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            return id.name();
        } finally {
            inserter.close();
        }
    }

    private static void ref(Repository repository, String name, String commit) throws IOException {
        RefUpdate update = repository.updateRef(name);
        update.setNewObjectId(ObjectId.fromString(commit));
        update.setForceUpdate(true);
        update.update();
    }
}
