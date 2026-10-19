package com.aresstack.enterpriseai.proasteion.emporion.holkas.git;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What a file of a repository is, by its name, and how its bytes become text (#137). Text, code and configuration
 * keep their indentation and line breaks inside a code fence with the language; Markdown stays Markdown; PDF and
 * Office go to the extractors of deigma; images and design originals ({@code .fig}) are listed as assets with their
 * metadata only. Everything else (archives, binaries, unknown endings) is not part of the source.
 */
final class GitFiles {

    /** The kinds of files the source lists. */
    enum Kind {
        /** Source code, configuration, plain text: fenced with {@link #language}. */
        TEXT,
        /** Markdown and MDX. */
        MARKDOWN,
        /** PDF and Office: extracted through deigma. */
        DOCUMENT,
        /** Images and design originals: found by path and metadata, content not read as text. */
        ASSET,
        /** Not part of the source. */
        NONE
    }

    /** Defaults for the exclude setting: dependencies, build output, lockfiles, IDE folders, minified bundles. */
    static final String DEFAULT_EXCLUDES = "node_modules/**, dist/**, build/**, target/**, vendor/**, out/**, "
            + ".gradle/**, .idea/**, .vscode/**, *.min.js, *.min.css, *.map, package-lock.json, yarn.lock, "
            + "pnpm-lock.yaml, *.lock, go.sum";

    /** First line of a Git LFS pointer: {@code version} and the spec URL of git-lfs.github.com. */
    private static final String LFS_VERSION = "version ";
    private static final String LFS_SPEC = "git-lfs.github.com/spec/";
    private static final Map<String, String> LANGUAGES;
    private static final Map<String, String> NAMES;
    private static final Map<String, String> ASSETS;
    private static final Map<String, String> DOCUMENTS;

    static {
        Map<String, String> languages = new HashMap<String, String>();
        // Java
        put(languages, "java", "java");
        put(languages, "gradle", "groovy");
        put(languages, "groovy", "groovy");
        put(languages, "kts", "kotlin");
        put(languages, "kt", "kotlin");
        put(languages, "properties", "properties");
        // Go
        put(languages, "go", "go");
        // Frontend
        put(languages, "js", "javascript");
        put(languages, "mjs", "javascript");
        put(languages, "cjs", "javascript");
        put(languages, "jsx", "jsx");
        put(languages, "ts", "typescript");
        put(languages, "tsx", "tsx");
        put(languages, "css", "css");
        put(languages, "scss", "scss");
        put(languages, "less", "less");
        put(languages, "html", "html");
        put(languages, "htm", "html");
        put(languages, "vue", "vue");
        put(languages, "svg", "svg");
        // Configuration and data
        put(languages, "json", "json");
        put(languages, "yaml", "yaml");
        put(languages, "yml", "yaml");
        put(languages, "xml", "xml");
        put(languages, "toml", "toml");
        put(languages, "ini", "ini");
        put(languages, "csv", "csv");
        put(languages, "sql", "sql");
        put(languages, "graphql", "graphql");
        put(languages, "proto", "protobuf");
        // Scripts and other common languages
        put(languages, "sh", "bash");
        put(languages, "bash", "bash");
        put(languages, "ps1", "powershell");
        put(languages, "bat", "bat");
        put(languages, "cmd", "bat");
        put(languages, "py", "python");
        put(languages, "rb", "ruby");
        put(languages, "rs", "rust");
        put(languages, "c", "c");
        put(languages, "h", "c");
        put(languages, "cpp", "cpp");
        put(languages, "hpp", "cpp");
        put(languages, "cs", "csharp");
        put(languages, "scala", "scala");
        put(languages, "txt", "text");
        put(languages, "rst", "text");
        put(languages, "adoc", "asciidoc");
        LANGUAGES = Collections.unmodifiableMap(languages);

        Map<String, String> names = new HashMap<String, String>();
        put(names, "dockerfile", "dockerfile");
        put(names, "makefile", "makefile");
        put(names, "jenkinsfile", "groovy");
        put(names, "go.mod", "go-mod");
        put(names, "go.sum", "text");
        put(names, "license", "text");
        put(names, "notice", "text");
        put(names, "codeowners", "text");
        put(names, ".gitignore", "text");
        put(names, ".gitattributes", "text");
        put(names, ".editorconfig", "ini");
        NAMES = Collections.unmodifiableMap(names);

        Map<String, String> assets = new HashMap<String, String>();
        put(assets, "png", "image/png");
        put(assets, "jpg", "image/jpeg");
        put(assets, "jpeg", "image/jpeg");
        put(assets, "gif", "image/gif");
        put(assets, "webp", "image/webp");
        put(assets, "ico", "image/x-icon");
        put(assets, "fig", "application/x-figma");
        ASSETS = Collections.unmodifiableMap(assets);

        Map<String, String> documents = new HashMap<String, String>();
        put(documents, "pdf", "application/pdf");
        put(documents, "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        put(documents, "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        put(documents, "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        put(documents, "odt", "application/vnd.oasis.opendocument.text");
        DOCUMENTS = Collections.unmodifiableMap(documents);
    }

    private GitFiles() {
    }

    static Kind kindOf(String path) {
        String name = nameOf(path).toLowerCase(Locale.ROOT);
        String extension = extensionOf(name);
        if ("md".equals(extension) || "mdx".equals(extension) || "markdown".equals(extension)) {
            return Kind.MARKDOWN;
        }
        if (LANGUAGES.containsKey(extension) || NAMES.containsKey(name) || name.startsWith("readme")
                || name.startsWith("dockerfile")) {
            return Kind.TEXT;
        }
        if (DOCUMENTS.containsKey(extension)) {
            return Kind.DOCUMENT;
        }
        if (ASSETS.containsKey(extension)) {
            return Kind.ASSET;
        }
        return Kind.NONE;
    }

    /** The fence language of a text file; {@code text} if none is known. */
    static String languageOf(String path) {
        String name = nameOf(path).toLowerCase(Locale.ROOT);
        String language = NAMES.get(name);
        if (language == null) {
            language = LANGUAGES.get(extensionOf(name));
        }
        if (language == null && name.startsWith("dockerfile")) {
            language = "dockerfile";
        }
        return language == null ? "text" : language;
    }

    /** The MIME type of a file for the resource metadata. */
    static String contentTypeOf(String path) {
        String extension = extensionOf(nameOf(path).toLowerCase(Locale.ROOT));
        switch (kindOf(path)) {
            case MARKDOWN:
                return "text/markdown";
            case DOCUMENT:
                return DOCUMENTS.get(extension);
            case ASSET:
                return ASSETS.get(extension);
            default:
                if ("svg".equals(extension)) {
                    return "image/svg+xml";
                }
                if ("html".equals(extension) || "htm".equals(extension)) {
                    return "text/html";
                }
                if ("json".equals(extension)) {
                    return "application/json";
                }
                return "text/plain";
        }
    }

    /** Whether the bytes are a Git LFS pointer instead of the file. */
    static boolean isLfsPointer(byte[] bytes) {
        if (bytes.length > 1024 || bytes.length < LFS_VERSION.length() + LFS_SPEC.length()) {
            return false;
        }
        String text = new String(bytes, StandardCharsets.US_ASCII);
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        return first.startsWith(LFS_VERSION) && first.contains(LFS_SPEC);
    }

    /** Whether the bytes look binary (a NUL byte in the first 8000 bytes, like Git's own heuristic). */
    static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8000);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** UTF-8 (with or without BOM), else Windows-1252 as the usual fallback of older repositories. */
    static String decode(byte[] bytes) {
        int offset = bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            Charset fallback = Charset.isSupported("windows-1252") ? Charset.forName("windows-1252")
                    : StandardCharsets.ISO_8859_1;
            return new String(bytes, offset, bytes.length - offset, fallback);
        }
    }

    /** A code fence that no backtick run of the text can close: at least three, one more than the longest run. */
    static String fence(String text) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        StringBuilder fence = new StringBuilder();
        for (int i = 0; i < Math.max(3, longest + 1); i++) {
            fence.append('`');
        }
        return fence.toString();
    }

    static String nameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1);
    }

    private static void put(Map<String, String> map, String key, String value) {
        map.put(key, value);
    }
}
