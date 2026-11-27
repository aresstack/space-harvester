package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.layout.EngineFamily;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfile;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileMatch;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileQuery;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A file-backed {@link SearchPageLayoutProfileStore} for the productive research context. Profiles
 * are one JSON object per line; STRUCTURE only — never a snapshot-local container id. Writes are
 * ATOMIC: the whole set is written to a sibling temp file and then moved into place, so a crash never
 * leaves a half-written store. The file is loaded once on construction and kept in memory.
 */
public final class FileSearchPageLayoutProfileStore implements SearchPageLayoutProfileStore {

    static final int STRUCTURE_SIGNATURE_VERSION = 1;

    private final Path path;
    private final List<SearchPageLayoutProfile> profiles = new ArrayList<SearchPageLayoutProfile>();

    public FileSearchPageLayoutProfileStore(Path path) {
        this.path = path;
        load();
    }

    public synchronized SearchPageLayoutProfileMatch find(SearchPageLayoutProfileQuery query) {
        for (int i = profiles.size() - 1; i >= 0; i--) {
            SearchPageLayoutProfile profile = profiles.get(i);
            if (query.matches(profile)) {
                return SearchPageLayoutProfileMatch.of(profile);
            }
        }
        return SearchPageLayoutProfileMatch.none("no compatible profile on disk");
    }

    public synchronized void saveValidated(SearchPageLayoutProfile profile) {
        profiles.add(profile);
        persistAtomically();
    }

    public synchronized int size() {
        return profiles.size();
    }

    private void load() {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                profiles.add(deserialize(line));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read layout profile store: " + path, e);
        }
    }

    private void persistAtomically() {
        if (path == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (SearchPageLayoutProfile profile : profiles) {
            sb.append(serialize(profile)).append('\n');
        }
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(temp, sb.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write layout profile store: " + path, e);
        }
    }

    private static String serialize(SearchPageLayoutProfile p) {
        StringBuilder sb = new StringBuilder("{");
        field(sb, "engineFamily", p.engineFamily.name()).append(',');
        field(sb, "documentFingerprintPattern", p.documentFingerprintPattern).append(',');
        number(sb, "structureSignatureVersion", p.structureSignatureVersion).append(',');
        field(sb, "resultRegionStructureSignature", p.resultRegionStructureSignature).append(',');
        sb.append("\"resultBlockStructureSignatures\":").append(array(p.resultBlockStructureSignatures))
                .append(',');
        field(sb, "ancestrySignature", p.ancestrySignature).append(',');
        field(sb, "settingsDigest", p.settingsDigest).append(',');
        number(sb, "createdAtEpochMillis", p.createdAtEpochMillis).append(',');
        number(sb, "lastValidatedAtEpochMillis", p.lastValidatedAtEpochMillis).append(',');
        number(sb, "successfulUseCount", p.successfulUseCount);
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static SearchPageLayoutProfile deserialize(String line) {
        Object root = MiniJson.parse(line);
        Map<String, Object> o = (Map<String, Object>) root;
        List<String> blocks = new ArrayList<String>();
        Object rawBlocks = o.get("resultBlockStructureSignatures");
        if (rawBlocks instanceof List) {
            for (Object element : (List<?>) rawBlocks) {
                blocks.add(String.valueOf(element));
            }
        }
        return new SearchPageLayoutProfile(engineFamily(o.get("engineFamily")),
                string(o.get("documentFingerprintPattern")),
                (int) doubleOf(o.get("structureSignatureVersion")),
                string(o.get("resultRegionStructureSignature")), blocks,
                string(o.get("ancestrySignature")), string(o.get("settingsDigest")),
                (long) doubleOf(o.get("createdAtEpochMillis")),
                (long) doubleOf(o.get("lastValidatedAtEpochMillis")),
                (int) doubleOf(o.get("successfulUseCount")));
    }

    private static EngineFamily engineFamily(Object value) {
        try {
            return EngineFamily.valueOf(string(value));
        } catch (IllegalArgumentException e) {
            return EngineFamily.UNKNOWN;
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double doubleOf(Object value) {
        return value instanceof Double ? (Double) value : 0.0;
    }

    private static StringBuilder field(StringBuilder sb, String key, String value) {
        return sb.append('"').append(key).append("\":\"").append(escape(value)).append('"');
    }

    private static StringBuilder number(StringBuilder sb, String key, long value) {
        return sb.append('"').append(key).append("\":").append(value);
    }

    private static String array(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(values.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
