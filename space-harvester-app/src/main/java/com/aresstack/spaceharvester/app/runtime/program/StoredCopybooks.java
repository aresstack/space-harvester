package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSource;
import com.aresstack.spaceharvester.mainframe.cobol.CopybookResolver;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Resolve only explicitly configured MVS libraries in the same stored knowledge source. */
public final class StoredCopybooks implements CopybookResolver {
    private static final Pattern QUALIFIER = Pattern.compile("[A-Z@#$][A-Z0-9@#$-]{0,7}");
    private final PageKey program;
    private final String resourcePrefix;
    private final List<String> libraries;
    private final Function<PageKey, Optional<PageSnapshot>> lookup;
    private final Map<String, Probe> probes = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public StoredCopybooks(PageSnapshot snapshot, List<String> libraries,
                           Function<PageKey, Optional<PageSnapshot>> lookup) {
        this.program = snapshot.key();
        this.libraries = List.copyOf(libraries);
        this.lookup = lookup;
        String resource = snapshot.metadata().resourceId();
        String path = snapshot.metadata().attributes().getOrDefault("ftp.path", "");
        this.resourcePrefix = resource.startsWith("ftp:") && !path.isEmpty() && resource.endsWith("/" + path)
                ? resource.substring(0, resource.length() - path.length()) : "";
    }

    public static List<String> libraries(String configured) {
        if (configured == null || configured.isBlank()) return List.of();
        return java.util.Arrays.stream(configured.split("[,;\\r\\n]+"))
                .map(StoredCopybooks::normalize).filter(value -> !value.isEmpty()).distinct().toList();
    }

    @Override
    public Optional<CobolSource> resolve(CobolSource including, String name, String library) {
        String member = normalize(name);
        if (resourcePrefix.isEmpty() || !QUALIFIER.matcher(member).matches()) return Optional.empty();
        List<String> candidates = candidates(library);
        boolean earlierUnavailable = false;
        for (String dataset : candidates) {
            if (!validDataset(dataset)) continue;
            String path = dataset + "(" + member + ")";
            String resource = resourcePrefix + path;
            PageKey key = new PageKey(program.sourceId(), Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(resource.getBytes(StandardCharsets.UTF_8)));
            Optional<PageSnapshot> stored = lookup.apply(key);
            String hash = stored.map(page -> page.payload().rawSha256()).orElse(null);
            probes.put(key.pageId(), new Probe(key.pageId(), hash));
            Optional<CobolSource> source = stored.filter(page -> page.key().equals(key)
                            && resource.equals(page.metadata().resourceId())
                            && path.equals(page.metadata().attributes().get("ftp.path")))
                    .flatMap(StoredCobolSource::verified);
            if (source.isPresent()) {
                if (earlierUnavailable) warnings.add("COPY " + member
                        + ": frühere SYSLIB nicht im Bestand nachweisbar; Kandidat " + path);
                return source;
            }
            earlierUnavailable = true;
        }
        return Optional.empty();
    }

    private List<String> candidates(String library) {
        String requested = normalize(library);
        if (requested.isEmpty()) return libraries;
        if (libraries.contains(requested)) return List.of(requested);
        List<String> matches = libraries.stream().filter(value -> value.endsWith("." + requested)).toList();
        if (matches.size() > 1) warnings.add("Mehrdeutige SYSLIB-Angabe: " + requested);
        return matches.size() == 1 ? matches : List.of();
    }

    private static boolean validDataset(String dataset) {
        return dataset.length() <= 44 && java.util.Arrays.stream(dataset.split("\\.", -1))
                .allMatch(part -> QUALIFIER.matcher(part).matches());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("^'|'$", "").toUpperCase(Locale.ROOT);
    }

    public List<Probe> probes() { return List.copyOf(probes.values()); }

    /** Stored copybooks a result was derived from: successful probes only, in resolution order. */
    public static List<PageKey> resolved(String sourceId, List<Probe> probes) {
        return probes.stream().filter(probe -> probe.rawSha256() != null)
                .map(probe -> new PageKey(sourceId, probe.pageId())).distinct().toList();
    }
    public List<String> warnings() { return List.copyOf(warnings); }

    /** Record unsuccessful probes as well as successful ones so newly acquired members invalidate the analysis. */
    public record Probe(String pageId, String rawSha256) { }
}
