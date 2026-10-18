import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Messwerkzeug für #181: vergleicht drei Wege, den Seitenbestand eines Confluence-Space (Data Center / Server,
 * REST v1) nur über Metadaten zu ermitteln, und prüft, ob ein lokaler Child-/Merkle-Hash Anfragen spart.
 *
 * <pre>
 *   A Flach   GET /rest/api/content?type=page&amp;spaceKey=K&amp;expand=version,ancestors   (wie InventoryCollector)
 *   B Baum    GET /rest/api/space/K/content/page?depth=root, dann /rest/api/content/{id}/child/page rekursiv
 *             (wie PageNavigationSource / KnowledgeSourceHarvestSource.children), expand=version,childTypes.page
 *   C Delta   GET /rest/api/content/search?cql=space="K" and type=page and lastmodified >= "…"
 *   D Teilbaum CQL ancestor = ID und parent = ID gegen die Kinderabfragen aus B
 *   F Papierkorb GET /rest/api/content?type=page&amp;status=trashed&amp;spaceKey=K (Löschungen, meist Space-Admin)
 *   E Lokaler Merkle-Hash über (pageId, parentId, version) und Simulation von Neu/Ändern/Verschieben/Löschen
 * </pre>
 *
 * <p>Lädt keine Seiteninhalte (kein body.*), schreibt nichts in Confluence, fragt nacheinander (nie parallel) mit
 * Pause, stoppt sofort bei 429/503 oder einer Anmeldeaufforderung. Ein PAT wird nur als Bearer-Header gesendet und
 * nie protokolliert.
 *
 * <p>Start ohne Build (Java 21): {@code java ConfluenceSyncBenchmark.java --base-url https://confluence.atlassian.com}
 * Selbsttest gegen eine eingebaute simulierte Confluence: {@code java ConfluenceSyncBenchmark.java --self-test}
 */
public class ConfluenceSyncBenchmark {

    // ------------------------------------------------------------------------------------------- Optionen

    static final class Options {
        String baseUrl;
        String patFile;
        List<String> spaces = new ArrayList<>();
        int limit = 50;
        long delayMs = 1000;
        int maxRequestsPerMethod = 400;
        int maxRequestsTotal = 3000;
        int sizeProbeSpaces = 25;
        int deltaDays = 30;
        int subtreeTarget = 100;
        int repeat = 1;
        boolean childTypes = true;
        boolean probeOnly = false;
        boolean selfTest = false;
        String compare;
        Path out = Path.of("benchmark-ergebnis");
        String proxy;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--base-url" -> o.baseUrl = stripSlash(args[++i]);
                    case "--pat-file" -> o.patFile = args[++i];
                    case "--spaces" -> o.spaces = new ArrayList<>(Arrays.asList(args[++i].split(",")));
                    case "--limit" -> o.limit = Integer.parseInt(args[++i]);
                    case "--delay-ms" -> o.delayMs = Long.parseLong(args[++i]);
                    case "--max-requests-per-method" -> o.maxRequestsPerMethod = Integer.parseInt(args[++i]);
                    case "--max-requests" -> o.maxRequestsTotal = Integer.parseInt(args[++i]);
                    case "--size-probe-spaces" -> o.sizeProbeSpaces = Integer.parseInt(args[++i]);
                    case "--delta-days" -> o.deltaDays = Integer.parseInt(args[++i]);
                    case "--subtree-target" -> o.subtreeTarget = Integer.parseInt(args[++i]);
                    case "--repeat" -> o.repeat = Integer.parseInt(args[++i]);
                    case "--no-child-types" -> o.childTypes = false;
                    case "--probe-only" -> o.probeOnly = true;
                    case "--self-test" -> o.selfTest = true;
                    case "--compare" -> o.compare = args[++i];
                    case "--out" -> o.out = Path.of(args[++i]);
                    case "--proxy" -> o.proxy = args[++i];
                    case "--help", "-h" -> { usage(); System.exit(0); }
                    default -> throw new IllegalArgumentException("Unbekannte Option: " + a + " (--help)");
                }
            }
            o.spaces.removeIf(String::isBlank);
            if (!o.selfTest && o.baseUrl == null) {
                throw new IllegalArgumentException("--base-url fehlt (--help)");
            }
            if (o.limit < 1 || o.limit > 200) {
                throw new IllegalArgumentException("--limit muss zwischen 1 und 200 liegen");
            }
            if (o.delayMs < 250 && o.baseUrl != null && o.baseUrl.contains("atlassian.com")) {
                throw new IllegalArgumentException("Für confluence.atlassian.com mindestens --delay-ms 250 (Instanz schonen)");
            }
            return o;
        }

        static void usage() {
            System.out.println("""
                    ConfluenceSyncBenchmark – Metadaten-Synchronisierung messen (#181)

                      --base-url URL              z. B. https://confluence.atlassian.com oder https://wiki.firma.de/confluence
                      --pat-file DATEI            optional: Datei mit Personal Access Token (erste Zeile), sonst anonym
                      --spaces K1,K2,K3           Spaces fest vorgeben; ohne: klein/mittel/groß automatisch wählen
                      --limit N                   Seitengröße je Anfrage (Standard 50)
                      --delay-ms MS               Pause zwischen Anfragen (Standard 1000 = höchstens 1 Anfrage/s)
                      --max-requests-per-method N Deckel je Verfahren und Space (Standard 400)
                      --max-requests N            Deckel für den ganzen Lauf (Standard 3000)
                      --size-probe-spaces N       so viele Spaces höchstens für die Größenbestimmung zählen (Standard 25)
                      --delta-days N              Zeitfenster der CQL-Änderungssuche (Standard 30)
                      --subtree-target N          gewünschte Größe des Teilbaums für Verfahren D (Standard 100)
                      --repeat N                  jedes Verfahren N-mal (2 = Kalt- und Warmlauf)
                      --no-child-types            Baum ohne childTypes.page: jede Seite nach Kindern fragen
                      --compare SNAPSHOT.json     früheren Lauf vergleichen (neue/gelöschte/verschobene/geänderte Seiten)
                      --probe-only                nur Phase 0 (Zugriffsprüfung)
                      --proxy HOST:PORT           HTTP-Proxy; ohne: Java-Standard (-Djava.net.useSystemProxies=true)
                      --out VERZEICHNIS           Ausgabe (Standard ./benchmark-ergebnis)
                      --self-test                 gegen eingebaute simulierte Confluence prüfen, ohne Netz
                    """);
        }
    }

    // ------------------------------------------------------------------------------------------- HTTP-Messung

    record Req(String run, String space, String method, String path, int status, long bytes, long millis,
               String contentType) {}

    static final class Stop extends RuntimeException {
        Stop(String message) { super(message); }
    }

    static final class Budget extends RuntimeException {
        Budget(String message) { super(message); }
    }

    static final class Resp {
        int status;
        String body;
        String contentType;
        Map<String, List<String>> headers;
        Object json;
    }

    static final class Http {
        final Options o;
        final HttpClient client;
        final String pat;
        final List<Req> log = new ArrayList<>();
        String run = "";
        String space = "";
        String method = "";
        int methodCount;
        long lastRequest;

        Http(Options o) throws IOException {
            this.o = o;
            HttpClient.Builder b = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(20));
            if (o.proxy != null) {
                String[] hp = o.proxy.split(":");
                b.proxy(ProxySelector.of(new InetSocketAddress(hp[0], Integer.parseInt(hp[1]))));
            } else {
                b.proxy(ProxySelector.getDefault());
            }
            this.client = b.build();
            this.pat = o.patFile == null ? null : Files.readAllLines(Path.of(o.patFile)).get(0).trim();
        }

        void begin(String run, String space, String method) {
            this.run = run;
            this.space = space;
            this.method = method;
            this.methodCount = 0;
        }

        /** Ein GET; {@code path} beginnt mit "/" und ist relativ zur Basis-URL. Für die Messung, nicht für Phase 0. */
        Resp get(String path) {
            if (methodCount >= o.maxRequestsPerMethod) {
                throw new Budget("Deckel je Verfahren erreicht (" + o.maxRequestsPerMethod + " Anfragen)");
            }
            if (log.size() >= o.maxRequestsTotal) {
                throw new Budget("Gesamtdeckel erreicht (" + o.maxRequestsTotal + " Anfragen)");
            }
            Resp r = raw(path, "application/json");
            if (r.status == 429 || r.status == 503) {
                throw new Stop("HTTP " + r.status + " auf " + path + " (Retry-After: "
                        + header(r, "Retry-After") + ") – Lauf sofort beendet");
            }
            if (r.status == 401 || r.status == 403 || header(r, "X-Seraph-LoginReason") != null
                    && !"OK".equalsIgnoreCase(header(r, "X-Seraph-LoginReason"))) {
                throw new Stop("Zugriff verweigert: HTTP " + r.status + ", X-Seraph-LoginReason="
                        + header(r, "X-Seraph-LoginReason") + " auf " + path);
            }
            if (r.status >= 300 && r.status < 400) {
                throw new Stop("Weiterleitung " + r.status + " nach " + header(r, "Location") + " auf " + path
                        + " (vermutlich Anmeldung/SSO)");
            }
            if (r.status != 200) {
                throw new Stop("HTTP " + r.status + " auf " + path);
            }
            if (r.contentType == null || !r.contentType.contains("json")) {
                throw new Stop("Antwort ist kein JSON (" + r.contentType + ") auf " + path);
            }
            r.json = Json.parse(r.body);
            return r;
        }

        Resp raw(String path, String accept) {
            pause();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(o.baseUrl + path)).GET()
                    .timeout(Duration.ofSeconds(60)).header("Accept", accept)
                    .header("User-Agent", "space-harvester-sync-benchmark/1 (#181; Metadaten, langsam)");
            if (pat != null) {
                b.header("Authorization", "Bearer " + pat);
            }
            long start = System.nanoTime();
            Resp r = new Resp();
            try {
                HttpResponse<byte[]> res = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
                r.status = res.statusCode();
                r.body = new String(res.body(), StandardCharsets.UTF_8);
                r.headers = res.headers().map();
                r.contentType = res.headers().firstValue("Content-Type").orElse(null);
                long millis = (System.nanoTime() - start) / 1_000_000;
                log.add(new Req(run, space, method, path, r.status, res.body().length, millis, r.contentType));
            } catch (IOException e) {
                log.add(new Req(run, space, method, path, -1, 0, (System.nanoTime() - start) / 1_000_000, null));
                throw new Stop("Netzwerkfehler auf " + path + ": " + e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Stop("abgebrochen");
            }
            methodCount++;
            return r;
        }

        void pause() {
            long wait = lastRequest + o.delayMs - System.currentTimeMillis();
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            lastRequest = System.currentTimeMillis();
        }

        /** Folgelink aus {@code _links.next} als Pfad relativ zur Basis-URL. */
        String next(Resp r) {
            Map<String, Object> links = Json.obj(Json.obj(r.json).get("_links"));
            if (links == null || links.get("next") == null) {
                return null;
            }
            String next = (String) links.get("next");
            String context = links.get("context") instanceof String c ? c : "";
            if (next.startsWith("http")) {
                next = next.substring(o.baseUrl.length());
            } else if (!context.isEmpty() && next.startsWith(context + "/") && o.baseUrl.endsWith(context)) {
                next = next.substring(context.length());
            }
            return next;
        }

        static String header(Resp r, String name) {
            if (r.headers == null) {
                return null;
            }
            for (Map.Entry<String, List<String>> e : r.headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------- Seitenmodell

    record Node(String id, String parent, long version, String when) {}

    static final class Snapshot {
        String space;
        String method;
        Instant takenAt;
        Map<String, Node> nodes = new LinkedHashMap<>();
        boolean complete = true;
        String problem;
    }

    record MethodResult(String run, String space, String method, Snapshot snapshot, int requests, long bytes,
                        long millis, long medianMs, long p95Ms, String note) {}

    // ------------------------------------------------------------------------------------------- Phase 0

    record Probe(String what, String path, int status, String contentType, String location, String note) {}

    static List<Probe> phase0(Http http, Options o, String spaceHint) {
        List<Probe> probes = new ArrayList<>();
        http.begin("probe", "", "Phase0");
        String space = spaceHint;
        Probe p = probe(http, "Spaces", "/rest/api/space?limit=2", true);
        probes.add(p);
        if (p.status != 200 || p.contentType == null || !p.contentType.contains("json")) {
            return probes;
        }
        if (space == null) {
            Resp r = lastJson;
            List<Object> results = Json.arr(Json.obj(r.json).get("results"));
            if (results != null && !results.isEmpty()) {
                space = (String) Json.obj(results.get(0)).get("key");
            }
        }
        if (space == null) {
            return probes;
        }
        String q = enc(space);
        probes.add(probe(http, "Flache Liste", "/rest/api/content?type=page&spaceKey=" + q
                + "&expand=version,ancestors&limit=2", true));
        Probe roots = probe(http, "Wurzelseiten", "/rest/api/space/" + q
                + "/content/page?depth=root&expand=version,childTypes.page&limit=2", true);
        probes.add(roots);
        String rootId = null;
        if (lastJson != null && lastJson.json != null) {
            List<Object> results = Json.arr(Json.obj(lastJson.json).get("results"));
            if (results != null && !results.isEmpty()) {
                rootId = (String) Json.obj(results.get(0)).get("id");
            }
        }
        if (rootId != null) {
            probes.add(probe(http, "Kinder", "/rest/api/content/" + rootId
                    + "/child/page?expand=version,childTypes.page&limit=2", true));
            Probe single = probe(http, "Einzelseite (Header/Felder)", "/rest/api/content/" + rootId
                    + "?expand=version,history.lastUpdated,ancestors,childTypes.page", true);
            probes.add(new Probe(single.what, single.path, single.status, single.contentType, single.location,
                    (single.note.isEmpty() ? "" : single.note + "; ") + changeSignals(lastJson)));
        }
        probes.add(probe(http, "CQL Space", "/rest/api/content/search?limit=2&cql="
                + enc("space=\"" + space + "\" and type=page"), true));
        probes.add(probe(http, "CQL lastmodified", "/rest/api/content/search?limit=2&cql="
                + enc("space=\"" + space + "\" and type=page and lastmodified >= now(\"-" + o.deltaDays + "d\")"), true));
        probes.add(probe(http, "Suche totalSize", "/rest/api/search?limit=1&cql="
                + enc("space=\"" + space + "\" and type=page"), true));
        probes.add(probe(http, "Version (applinks)", "/rest/applinks/1.0/manifest", false));
        return probes;
    }

    static Resp lastJson;

    static Probe probe(Http http, String what, String path, boolean json) {
        lastJson = null;
        Resp r = http.raw(path, json ? "application/json" : "application/xml");
        List<String> notes = new ArrayList<>();
        for (String h : List.of("X-Seraph-LoginReason", "Retry-After", "X-RateLimit-Remaining", "X-RateLimit-Limit",
                "WWW-Authenticate", "X-AUSERNAME", "ETag", "Last-Modified", "Cache-Control", "X-Confluence-Request-Time")) {
            String v = Http.header(r, h);
            if (v != null) {
                notes.add(h + "=" + v);
            }
        }
        if (json && r.status == 200 && r.contentType != null && r.contentType.contains("json")) {
            try {
                r.json = Json.parse(r.body);
                lastJson = r;
                Map<String, Object> m = Json.obj(r.json);
                if (m != null && m.containsKey("size")) {
                    notes.add("size=" + m.get("size"));
                }
                if (m != null && m.containsKey("totalSize")) {
                    notes.add("totalSize=" + m.get("totalSize"));
                }
            } catch (RuntimeException e) {
                notes.add("JSON unlesbar: " + e.getMessage());
            }
        } else if (r.status == 200 && json) {
            notes.add("200 ohne JSON – kein REST-Erfolg");
        }
        if (!json && r.status == 200) {
            Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(r.body);
            if (m.find()) {
                notes.add("Version " + m.group(1));
            }
        }
        if (r.status == 429 || r.status == 503) {
            notes.add("RATE-LIMIT – nach Phase 0 nicht weiter messen");
        }
        return new Probe(what, path, r.status, r.contentType, Http.header(r, "Location"), String.join(", ", notes));
    }

    /** Sucht in einer Einzelseite nach allem, was wie ein Teilbaum-Änderungssignal aussieht. */
    static String changeSignals(Resp r) {
        if (r == null || r.json == null) {
            return "Felder nicht lesbar";
        }
        Set<String> keys = new TreeSet<>();
        collectKeys(r.json, "", keys);
        List<String> hits = keys.stream().filter(k -> k.matches("(?i).*(hash|etag|checksum|digest|subtree|descendant"
                + "|changecount|revision|token).*")).toList();
        return "verdächtige Felder: " + (hits.isEmpty() ? "keine" : String.join(" ", hits))
                + " (Feldpfade gesamt " + keys.size() + ")";
    }

    static void collectKeys(Object v, String prefix, Set<String> keys) {
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = prefix.isEmpty() ? (String) e.getKey() : prefix + "." + e.getKey();
                keys.add(k);
                collectKeys(e.getValue(), k, keys);
            }
        } else if (v instanceof List<?> l && !l.isEmpty()) {
            collectKeys(l.get(0), prefix + "[]", keys);
        }
    }

    // ------------------------------------------------------------------------------------------- Space-Auswahl

    record SpaceSize(String key, long pages) {}

    static List<SpaceSize> chooseSpaces(Http http, Options o) {
        http.begin("auswahl", "", "Auswahl");
        List<SpaceSize> sizes = new ArrayList<>();
        if (!o.spaces.isEmpty()) {
            for (String key : o.spaces) {
                sizes.add(new SpaceSize(key, countPages(http, key)));
            }
            return sizes;
        }
        List<String> keys = new ArrayList<>();
        String path = "/rest/api/space?type=global&limit=" + Math.min(o.limit, 50);
        while (path != null && keys.size() < 500) {
            Resp r = http.get(path);
            for (Object s : Json.arr(Json.obj(r.json).get("results"))) {
                keys.add((String) Json.obj(s).get("key"));
            }
            path = http.next(r);
        }
        System.out.println("Anonym/mit Token sichtbare globale Spaces: " + keys.size());
        // gleichmäßige Stichprobe über die alphabetische Liste, damit nicht nur die ersten Keys gezählt werden
        List<String> sample = new ArrayList<>();
        int n = Math.min(o.sizeProbeSpaces, keys.size());
        for (int i = 0; i < n; i++) {
            sample.add(keys.get((int) ((long) i * keys.size() / n)));
        }
        List<SpaceSize> counted = new ArrayList<>();
        for (String key : sample) {
            counted.add(new SpaceSize(key, countPages(http, key)));
        }
        counted.sort(Comparator.comparingLong(SpaceSize::pages));
        List<SpaceSize> usable = counted.stream().filter(s -> s.pages >= 10).toList();
        if (usable.isEmpty()) {
            return counted.isEmpty() ? List.of() : List.of(counted.get(counted.size() - 1));
        }
        SpaceSize small = usable.get(0);
        SpaceSize large = usable.get(usable.size() - 1);
        double mid = Math.sqrt((double) small.pages * large.pages);
        SpaceSize medium = usable.stream().filter(s -> s != small && s != large)
                .min(Comparator.comparingDouble(s -> Math.abs(Math.log(s.pages) - Math.log(mid)))).orElse(null);
        List<SpaceSize> chosen = new ArrayList<>();
        chosen.add(small);
        if (medium != null) {
            chosen.add(medium);
        }
        if (large != small) {
            chosen.add(large);
        }
        System.out.println("Gezählte Stichprobe: " + counted);
        return chosen;
    }

    static long countPages(Http http, String key) {
        Resp r = http.get("/rest/api/search?limit=1&cql=" + enc("space=\"" + key + "\" and type=page"));
        Object total = Json.obj(r.json).get("totalSize");
        return total instanceof Number num ? num.longValue() : -1;
    }

    // ------------------------------------------------------------------------------------------- Verfahren

    static MethodResult flat(Http http, Options o, String run, String space) {
        http.begin(run, space, "A Flach");
        int from = http.log.size();
        long t0 = System.nanoTime();
        Snapshot s = newSnapshot(space, "A Flach");
        String path = "/rest/api/content?type=page&spaceKey=" + enc(space) + "&expand=version,ancestors&start=0&limit="
                + o.limit;
        try {
            while (path != null) {
                Resp r = http.get(path);
                for (Object item : Json.arr(Json.obj(r.json).get("results"))) {
                    Node n = node(Json.obj(item), null);
                    putChecked(s, n);
                }
                path = http.next(r);
            }
        } catch (Budget e) {
            s.complete = false;
            s.problem = e.getMessage();
        }
        return result(http, run, space, "A Flach", s, from, t0, "");
    }

    /** Kinder je Elternknoten (Anfragen) aus dem letzten Baumlauf, für Verfahren D. */
    static Map<String, Integer> treeRequestsPerParent = new HashMap<>();

    static MethodResult tree(Http http, Options o, String run, String space) {
        http.begin(run, space, "B Baum");
        int from = http.log.size();
        long t0 = System.nanoTime();
        Snapshot s = newSnapshot(space, "B Baum");
        treeRequestsPerParent = new HashMap<>();
        String expand = o.childTypes ? "version,childTypes.page" : "version";
        Deque<String[]> queue = new ArrayDeque<>(); // {parentId or "", path}
        queue.add(new String[] {"", "/rest/api/space/" + enc(space) + "/content/page?depth=root&expand=" + expand
                + "&start=0&limit=" + o.limit});
        int parentsAsked = 0;
        int leavesSkipped = 0;
        try {
            while (!queue.isEmpty()) {
                String[] job = queue.poll();
                String parent = job[0].isEmpty() ? null : job[0];
                String path = job[1];
                boolean first = true;
                while (path != null) {
                    Resp r = http.get(path);
                    treeRequestsPerParent.merge(job[0], 1, Integer::sum);
                    if (first && parent != null) {
                        parentsAsked++;
                    }
                    first = false;
                    for (Object item : Json.arr(Json.obj(r.json).get("results"))) {
                        Map<String, Object> m = Json.obj(item);
                        Node n = node(m, parent);
                        putChecked(s, n);
                        Boolean hasChildren = o.childTypes ? childTypesPage(m) : Boolean.TRUE;
                        if (hasChildren == null || hasChildren) {
                            queue.add(new String[] {n.id, "/rest/api/content/" + n.id + "/child/page?expand=" + expand
                                    + "&start=0&limit=" + o.limit});
                        } else {
                            leavesSkipped++;
                        }
                    }
                    path = http.next(r);
                }
            }
        } catch (Budget e) {
            s.complete = false;
            s.problem = e.getMessage() + "; offene Elternknoten: " + queue.size();
        }
        return result(http, run, space, "B Baum", s, from, t0, "Elternknoten abgefragt " + parentsAsked
                + ", Blätter ohne Anfrage (childTypes) " + leavesSkipped);
    }

    static MethodResult delta(Http http, Options o, String run, String space, Instant since) {
        http.begin(run, space, "C Delta");
        int from = http.log.size();
        long t0 = System.nanoTime();
        Snapshot s = newSnapshot(space, "C Delta");
        String cql = "space=\"" + space + "\" and type=page and lastmodified >= \"" + CQL_DATE.format(
                since.atZone(ZoneOffset.UTC)) + "\"";
        String path = "/rest/api/content/search?expand=version,ancestors&start=0&limit=" + o.limit + "&cql=" + enc(cql);
        try {
            while (path != null) {
                Resp r = http.get(path);
                for (Object item : Json.arr(Json.obj(r.json).get("results"))) {
                    putChecked(s, node(Json.obj(item), null));
                }
                path = http.next(r);
            }
        } catch (Budget e) {
            s.complete = false;
            s.problem = e.getMessage();
        }
        return result(http, run, space, "C Delta", s, from, t0, "CQL " + cql);
    }

    /**
     * F Papierkorb: gelöschte Seiten des Space ({@code status=trashed}). Braucht in Confluence meist Space-Admin-Rechte;
     * eine Verweigerung ist hier ein Messergebnis und kein Abbruchgrund (429/503 stoppen weiterhin).
     */
    static MethodResult trash(Http http, Options o, String run, String space) {
        http.begin(run, space, "F Papierkorb");
        int from = http.log.size();
        long t0 = System.nanoTime();
        Snapshot s = newSnapshot(space, "F Papierkorb");
        String path = "/rest/api/content?type=page&status=trashed&spaceKey=" + enc(space) + "&expand=version&start=0&limit="
                + o.limit;
        String note = "";
        try {
            while (path != null) {
                Resp r = http.raw(path, "application/json");
                if (r.status == 429 || r.status == 503) {
                    throw new Stop("HTTP " + r.status + " auf " + path + " (Retry-After: " + Http.header(r, "Retry-After")
                            + ") – Lauf sofort beendet");
                }
                if (r.status != 200 || r.contentType == null || !r.contentType.contains("json")) {
                    s.complete = false;
                    note = "nicht verfügbar: HTTP " + r.status + " " + nz(r.contentType);
                    break;
                }
                r.json = Json.parse(r.body);
                for (Object item : Json.arr(Json.obj(r.json).get("results"))) {
                    putChecked(s, node(Json.obj(item), null));
                }
                path = http.next(r);
            }
        } catch (Budget e) {
            s.complete = false;
            s.problem = e.getMessage();
        }
        return result(http, run, space, "F Papierkorb", s, from, t0, note);
    }

    static MethodResult cqlSet(Http http, Options o, String run, String space, String method, String cql) {
        http.begin(run, space, method);
        int from = http.log.size();
        long t0 = System.nanoTime();
        Snapshot s = newSnapshot(space, method);
        String path = "/rest/api/content/search?expand=version,ancestors&start=0&limit=" + o.limit + "&cql=" + enc(cql);
        try {
            while (path != null) {
                Resp r = http.get(path);
                for (Object item : Json.arr(Json.obj(r.json).get("results"))) {
                    putChecked(s, node(Json.obj(item), null));
                }
                path = http.next(r);
            }
        } catch (Budget e) {
            s.complete = false;
            s.problem = e.getMessage();
        }
        return result(http, run, space, method, s, from, t0, "CQL " + cql);
    }

    static final DateTimeFormatter CQL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    static Snapshot newSnapshot(String space, String method) {
        Snapshot s = new Snapshot();
        s.space = space;
        s.method = method;
        s.takenAt = Instant.now();
        return s;
    }

    static void putChecked(Snapshot s, Node n) {
        Node earlier = s.nodes.putIfAbsent(n.id, n);
        if (earlier != null && earlier.version != n.version && s.problem == null) {
            s.problem = "Seite " + n.id + " während des Laufs geändert (Version " + earlier.version + " → "
                    + n.version + ")";
        }
    }

    static Node node(Map<String, Object> m, String parentFromTraversal) {
        String id = (String) m.get("id");
        String parent = parentFromTraversal;
        List<Object> ancestors = Json.arr(m.get("ancestors"));
        if (parent == null && ancestors != null && !ancestors.isEmpty()) {
            parent = (String) Json.obj(ancestors.get(ancestors.size() - 1)).get("id");
        }
        Map<String, Object> v = Json.obj(m.get("version"));
        long version = v != null && v.get("number") instanceof Number num ? num.longValue() : -1;
        String when = v != null && v.get("when") instanceof String w ? w : null;
        return new Node(id, parent, version, when);
    }

    static Boolean childTypesPage(Map<String, Object> m) {
        Map<String, Object> ct = Json.obj(m.get("childTypes"));
        Map<String, Object> page = ct == null ? null : Json.obj(ct.get("page"));
        return page == null ? null : (Boolean) page.get("value");
    }

    static MethodResult result(Http http, String run, String space, String method, Snapshot s, int from, long t0,
                               String note) {
        List<Req> reqs = http.log.subList(from, http.log.size());
        long bytes = reqs.stream().mapToLong(Req::bytes).sum();
        long[] lat = reqs.stream().mapToLong(Req::millis).sorted().toArray();
        long median = lat.length == 0 ? 0 : lat[lat.length / 2];
        long p95 = lat.length == 0 ? 0 : lat[Math.min(lat.length - 1, (int) Math.ceil(lat.length * 0.95) - 1)];
        long millis = (System.nanoTime() - t0) / 1_000_000;
        return new MethodResult(run, space, method, s, reqs.size(), bytes, millis, median, p95,
                (s.problem == null ? "" : "⚠ " + s.problem + "; ") + note);
    }

    // ------------------------------------------------------------------------------------------- Merkle (E)

    @SuppressWarnings("unchecked")
    static Map<String, String> merkle(Map<String, Node> nodes) {
        Map<String, List<String>> children = new HashMap<>();
        for (Node n : nodes.values()) {
            children.computeIfAbsent(n.parent == null || !nodes.containsKey(n.parent) ? "" : n.parent,
                    k -> new ArrayList<>()).add(n.id);
        }
        Map<String, String> hashes = new HashMap<>();
        Function<String, String>[] h = new Function[1];
        h[0] = id -> {
            String cached = hashes.get(id);
            if (cached != null) {
                return cached;
            }
            List<String> kids = new ArrayList<>(children.getOrDefault(id, List.of()));
            kids.sort(null);
            StringBuilder sb = new StringBuilder();
            if (!id.isEmpty()) {
                Node n = nodes.get(id);
                sb.append(n.id).append('|').append(n.parent).append('|').append(n.version);
            }
            for (String k : kids) {
                sb.append('|').append(h[0].apply(k));
            }
            String value = sha256(sb.toString());
            hashes.put(id, value);
            return value;
        };
        // iterativ von unten, damit tiefe Bäume keinen Stapelüberlauf auslösen
        for (String id : postOrder(children)) {
            h[0].apply(id);
        }
        return hashes; // Schlüssel "" = Space-Wurzel
    }

    static List<String> postOrder(Map<String, List<String>> children) {
        List<String> order = new ArrayList<>();
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[] {"", false});
        Set<String> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            Object[] top = stack.pop();
            String id = (String) top[0];
            if ((Boolean) top[1]) {
                order.add(id);
                continue;
            }
            if (!seen.add(id)) {
                continue;
            }
            stack.push(new Object[] {id, true});
            for (String k : children.getOrDefault(id, List.of())) {
                stack.push(new Object[] {k, false});
            }
        }
        return order;
    }

    record Simulation(String change, int hashesChanged, int depth, String detectedByTree, String detectedByDelta,
                      String detectedByFlat) {}

    static List<Simulation> simulate(Map<String, Node> base, int treeRequests, int deltaRequests, int flatRequests) {
        List<Simulation> out = new ArrayList<>();
        if (base.size() < 3) {
            return out;
        }
        Map<String, String> before = merkle(base);
        Random rnd = new Random(181);
        List<Node> all = new ArrayList<>(base.values());
        Set<String> parents = new HashSet<>();
        for (Node n : all) {
            if (n.parent != null) {
                parents.add(n.parent);
            }
        }
        List<Node> leaves = all.stream().filter(n -> !parents.contains(n.id)).toList();
        Node deepest = leaves.stream().max(Comparator.comparingInt(n -> depth(base, n.id))).orElse(leaves.get(0));
        Node otherParent = all.stream().filter(n -> !n.id.equals(deepest.id) && !n.id.equals(deepest.parent))
                .skip(rnd.nextInt(Math.max(1, all.size() - 2))).findFirst().orElse(all.get(0));
        String tr = treeRequests + " (voller Durchlauf)";
        String fl = flatRequests + " (volle Inventur)";

        Map<String, Node> edit = new LinkedHashMap<>(base);
        edit.put(deepest.id, new Node(deepest.id, deepest.parent, deepest.version + 1, deepest.when));
        out.add(sim("Tiefstes Blatt bearbeitet", before, merkle(edit), base, deepest.id, tr,
                deltaRequests + " (ja: neue Version, lastmodified)", fl));

        Map<String, Node> added = new LinkedHashMap<>(base);
        added.put("sim-neu", new Node("sim-neu", deepest.id, 1, null));
        out.add(sim("Neue Seite unter tiefstem Blatt", before, merkle(added), added, "sim-neu", tr,
                deltaRequests + " (ja: neue Seite hat lastmodified)", fl));

        Map<String, Node> moved = new LinkedHashMap<>(base);
        moved.put(deepest.id, new Node(deepest.id, otherParent.id, deepest.version, deepest.when));
        out.add(sim("Blatt verschoben", before, merkle(moved), moved, deepest.id, tr,
                deltaRequests + " (nur wenn Verschieben lastmodified setzt: --compare auf eigener Instanz messen)", fl));

        Map<String, Node> deleted = new LinkedHashMap<>(base);
        deleted.remove(deepest.id);
        out.add(sim("Blatt gelöscht", before, merkle(deleted), base, deepest.id, tr,
                "nein (gelöschte Seiten tauchen in CQL nicht auf)", fl));
        return out;
    }

    static Simulation sim(String change, Map<String, String> before, Map<String, String> after, Map<String, Node> tree,
                          String id, String tr, String de, String fl) {
        int changed = 0;
        Set<String> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            if (!Objects.equals(before.get(k), after.get(k))) {
                changed++;
            }
        }
        return new Simulation(change, changed, depth(tree, id), tr, de, fl);
    }

    static int depth(Map<String, Node> nodes, String id) {
        int d = 0;
        Node n = nodes.get(id);
        Set<String> seen = new HashSet<>();
        while (n != null && n.parent != null && nodes.containsKey(n.parent) && seen.add(n.id)) {
            d++;
            n = nodes.get(n.parent);
        }
        return d + 1;
    }

    // ------------------------------------------------------------------------------------------- Ablauf

    public static void main(String[] args) throws Exception {
        Options o;
        try {
            o = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        if (o.selfTest) {
            System.exit(SelfTest.run(o) ? 0 : 1);
        }
        execute(o);
    }

    static Report execute(Options o) throws IOException {
        Files.createDirectories(o.out);
        Http http = new Http(o);
        Report report = new Report(o);
        try {
            List<Probe> probes = phase0(http, o, o.spaces.isEmpty() ? null : o.spaces.get(0));
            report.probes = probes;
            boolean gate = probes.stream().filter(p -> !p.what.startsWith("Version"))
                    .allMatch(p -> p.status == 200 && p.contentType != null && p.contentType.contains("json"));
            report.gate = gate;
            if (!gate || o.probeOnly) {
                report.stopped = gate ? "nur Phase 0 angefordert" : "Phase 0 nicht bestanden – keine Messung";
                return report;
            }
            List<SpaceSize> spaces = chooseSpaces(http, o);
            report.spaces = spaces;
            Instant since = Instant.now().minus(Duration.ofDays(o.deltaDays));
            report.since = since;
            for (SpaceSize space : spaces) {
                for (int run = 1; run <= o.repeat; run++) {
                    String label = o.repeat == 1 ? "1" : (run == 1 ? "kalt" : "warm" + (run - 1));
                    System.out.println("== " + space.key + " Lauf " + label);
                    MethodResult a = flat(http, o, label, space.key);
                    report.add(a);
                    MethodResult b = tree(http, o, label, space.key);
                    report.add(b);
                    Map<String, Integer> perParent = treeRequestsPerParent;
                    MethodResult c = delta(http, o, label, space.key, since);
                    report.add(c);
                    if (run == 1) {
                        report.add(trash(http, o, label, space.key));
                        report.compare(a, b, c, since);
                        subtree(http, o, label, space.key, b, perParent, report);
                        report.simulations.put(space.key, simulate(a.snapshot.nodes, b.requests, c.requests, a.requests));
                        writeSnapshot(o.out.resolve("snapshot-" + safe(space.key) + ".json"), a.snapshot);
                        if (o.compare != null) {
                            report.diffs.add(diff(readSnapshot(Path.of(o.compare)), a.snapshot, c.snapshot));
                        }
                    }
                }
            }
        } catch (Stop e) {
            report.stopped = e.getMessage();
            System.err.println("GESTOPPT: " + e.getMessage());
        } finally {
            writeCsv(o.out.resolve("anfragen.csv"), http.log);
            Files.writeString(o.out.resolve("bericht.md"), report.markdown(http.log));
            System.out.println("Ergebnis: " + o.out.toAbsolutePath().resolve("bericht.md"));
        }
        return report;
    }

    static void subtree(Http http, Options o, String run, String space, MethodResult b, Map<String, Integer> perParent,
                        Report report) {
        Map<String, Node> nodes = b.snapshot.nodes;
        Map<String, List<String>> children = new HashMap<>();
        for (Node n : nodes.values()) {
            children.computeIfAbsent(n.parent == null ? "" : n.parent, k -> new ArrayList<>()).add(n.id);
        }
        Map<String, Set<String>> desc = new HashMap<>();
        for (String id : postOrder(children)) {
            if (id.isEmpty()) {
                continue;
            }
            Set<String> d = new HashSet<>();
            for (String k : children.getOrDefault(id, List.of())) {
                d.add(k);
                d.addAll(desc.getOrDefault(k, Set.of()));
            }
            desc.put(id, d);
        }
        String root = desc.entrySet().stream().filter(e -> e.getValue().size() > 1)
                .min(Comparator.comparingInt(e -> Math.abs(e.getValue().size() - o.subtreeTarget)))
                .map(Map.Entry::getKey).orElse(null);
        if (root == null || !b.snapshot.complete) {
            return;
        }
        Set<String> expected = desc.get(root);
        int treeReq = 0;
        for (String id : expected) {
            treeReq += perParent.getOrDefault(id, 0);
        }
        treeReq += perParent.getOrDefault(root, 0);
        MethodResult anc = cqlSet(http, o, run, space, "D ancestor", "type=page and ancestor = " + root);
        report.add(anc);
        MethodResult par = cqlSet(http, o, run, space, "D parent", "type=page and parent = " + root);
        report.add(par);
        Set<String> gotAnc = anc.snapshot.nodes.keySet();
        Set<String> kids = new HashSet<>(children.getOrDefault(root, List.of()));
        report.subtrees.add(new Report.Subtree(space, root, expected.size(), kids.size(), treeReq, anc.requests,
                minus(expected, gotAnc).size(), minus(gotAnc, expected).size(), perParent.getOrDefault(root, 0),
                par.requests, minus(kids, par.snapshot.nodes.keySet()).size(),
                minus(par.snapshot.nodes.keySet(), kids).size()));
    }

    // ------------------------------------------------------------------------------------------- Vergleich

    record Diff(String space, int added, int removed, int moved, int edited, int movedSeenByDelta,
                int movedWithNewVersion, List<String> examples) {}

    static Diff diff(Snapshot old, Snapshot now, Snapshot delta) {
        int added = 0, removed = 0, moved = 0, edited = 0, movedSeen = 0, movedNewVersion = 0;
        List<String> ex = new ArrayList<>();
        for (Node n : now.nodes.values()) {
            Node o = old.nodes.get(n.id);
            if (o == null) {
                added++;
                continue;
            }
            if (!Objects.equals(o.parent, n.parent)) {
                moved++;
                if (delta.nodes.containsKey(n.id)) {
                    movedSeen++;
                }
                if (o.version != n.version) {
                    movedNewVersion++;
                }
                if (ex.size() < 10) {
                    ex.add("verschoben " + n.id + ": " + o.parent + " → " + n.parent + ", Version " + o.version + " → "
                            + n.version + ", in CQL-Delta " + (delta.nodes.containsKey(n.id) ? "ja" : "nein"));
                }
            } else if (o.version != n.version) {
                edited++;
            }
        }
        for (String id : old.nodes.keySet()) {
            if (!now.nodes.containsKey(id)) {
                removed++;
            }
        }
        return new Diff(now.space, added, removed, moved, edited, movedSeen, movedNewVersion, ex);
    }

    static <T> Set<T> minus(Set<T> a, Set<T> b) {
        Set<T> r = new HashSet<>(a);
        r.removeAll(b);
        return r;
    }

    // ------------------------------------------------------------------------------------------- Bericht

    static final class Report {
        record Subtree(String space, String root, int size, int children, int treeRequests, int ancestorRequests,
                       int missingInAncestor, int extraInAncestor, int childRequests, int parentRequests,
                       int missingInParent, int extraInParent) {}

        record Compare(String space, int a, int b, int onlyA, int onlyB, int parentMismatch, int expectedDelta,
                       int c, int missingInC, int extraInC) {}

        final Options o;
        List<Probe> probes = List.of();
        boolean gate;
        String stopped;
        List<SpaceSize> spaces = List.of();
        Instant since;
        final List<MethodResult> results = new ArrayList<>();
        final List<Compare> compares = new ArrayList<>();
        final List<Subtree> subtrees = new ArrayList<>();
        final Map<String, List<Simulation>> simulations = new LinkedHashMap<>();
        final List<Diff> diffs = new ArrayList<>();

        Report(Options o) { this.o = o; }

        void add(MethodResult r) {
            results.add(r);
            System.out.printf(Locale.ROOT, "  %-11s %5d Seiten %4d Anfragen %9d Bytes %7d ms %s%n", r.method,
                    r.snapshot.nodes.size(), r.requests, r.bytes, r.millis, r.note);
        }

        void compare(MethodResult a, MethodResult b, MethodResult c, Instant since) {
            Set<String> ka = a.snapshot.nodes.keySet();
            Set<String> kb = b.snapshot.nodes.keySet();
            int mismatch = 0;
            for (Node n : a.snapshot.nodes.values()) {
                Node m = b.snapshot.nodes.get(n.id);
                if (m != null && !Objects.equals(n.parent, m.parent)) {
                    mismatch++;
                }
            }
            // Zeitzone von CQL ist die des Servers/Benutzers: nur sicher erwartete (± 1 Tag) Seiten bewerten
            Instant sure = since.plus(Duration.ofDays(1));
            Instant maybe = since.minus(Duration.ofDays(1));
            Set<String> expectedSure = new HashSet<>();
            Set<String> expectedMaybe = new HashSet<>();
            for (Node n : a.snapshot.nodes.values()) {
                Instant w = parseWhen(n.when);
                if (w != null && !w.isBefore(sure)) {
                    expectedSure.add(n.id);
                }
                if (w != null && !w.isBefore(maybe)) {
                    expectedMaybe.add(n.id);
                }
            }
            Set<String> kc = c.snapshot.nodes.keySet();
            compares.add(new Compare(a.space, ka.size(), kb.size(), minus(ka, kb).size(), minus(kb, ka).size(), mismatch,
                    expectedSure.size(), kc.size(), minus(expectedSure, kc).size(), minus(kc, expectedMaybe).size()));
        }

        String markdown(List<Req> log) {
            StringBuilder md = new StringBuilder();
            md.append("# Confluence-REST-Benchmark Synchronisierung (#181)\n\n");
            md.append("- Basis-URL: ").append(o.baseUrl).append('\n');
            md.append("- Zugriff: ").append(o.patFile == null ? "anonym" : "PAT (nicht protokolliert)").append('\n');
            md.append("- Zeitpunkt: ").append(Instant.now()).append('\n');
            md.append("- Seitengröße ").append(o.limit).append(", Pause ").append(o.delayMs).append(" ms, Deckel ")
                    .append(o.maxRequestsPerMethod).append(" je Verfahren / ").append(o.maxRequestsTotal)
                    .append(" gesamt, childTypes ").append(o.childTypes ? "an" : "aus").append('\n');
            md.append("- Anfragen gesamt: ").append(log.size()).append(", Bytes gesamt: ")
                    .append(log.stream().mapToLong(Req::bytes).sum()).append('\n');
            if (stopped != null) {
                md.append("- **Abbruch:** ").append(stopped).append('\n');
            }
            md.append("\n## Phase 0: Zugriff\n\n| Prüfung | Pfad | Status | Content-Type | Location | Hinweise |\n|---|---|---|---|---|---|\n");
            for (Probe p : probes) {
                md.append("| ").append(p.what).append(" | `").append(p.path).append("` | ").append(p.status).append(" | ")
                        .append(nz(p.contentType)).append(" | ").append(nz(p.location)).append(" | ")
                        .append(p.note.replace("|", "/")).append(" |\n");
            }
            md.append("\nGate bestanden: **").append(gate ? "ja" : "nein").append("**\n");
            if (!spaces.isEmpty()) {
                md.append("\n## Phase 1: Spaces\n\n| Space | Seiten laut CQL totalSize |\n|---|---|\n");
                for (SpaceSize s : spaces) {
                    md.append("| ").append(s.key).append(" | ").append(s.pages).append(" |\n");
                }
                for (String key : spaces.stream().map(SpaceSize::key).toList()) {
                    results.stream().filter(r -> r.space.equals(key) && r.method.equals("B Baum") && r.snapshot.complete)
                            .findFirst().ifPresent(r -> md.append("\n").append(key).append(": ").append(shape(r.snapshot)));
                }
                md.append('\n');
            }
            if (!results.isEmpty()) {
                md.append("\n## Phase 2: Verfahren\n\n| Space | Lauf | Verfahren | Seiten | vollständig | Anfragen | Bytes | Laufzeit ms | Median ms | p95 ms | Anfragen je 100 Seiten | Hinweis |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n");
                for (MethodResult r : results) {
                    int pages = r.snapshot.nodes.size();
                    md.append("| ").append(r.space).append(" | ").append(r.run).append(" | ").append(r.method).append(" | ")
                            .append(pages).append(" | ").append(r.snapshot.complete ? "ja" : "**nein (Deckel)**")
                            .append(" | ").append(r.requests).append(" | ").append(r.bytes).append(" | ").append(r.millis)
                            .append(" | ").append(r.medianMs).append(" | ").append(r.p95Ms).append(" | ")
                            .append(pages == 0 ? "–" : String.format(Locale.ROOT, "%.1f", 100.0 * r.requests / pages))
                            .append(" | ").append(r.note.replace("|", "/")).append(" |\n");
                }
            }
            if (!compares.isEmpty()) {
                md.append("\n## Vollständigkeit (Mengenvergleich der Seiten-IDs)\n\n| Space | A | B | nur A | nur B | Eltern verschieden | Delta erwartet (sicher) | C | fehlt in C | C zusätzlich |\n|---|---|---|---|---|---|---|---|---|---|\n");
                for (Compare c : compares) {
                    md.append("| ").append(c.space).append(" | ").append(c.a).append(" | ").append(c.b).append(" | ")
                            .append(c.onlyA).append(" | ").append(c.onlyB).append(" | ").append(c.parentMismatch)
                            .append(" | ").append(c.expectedDelta).append(" | ").append(c.c).append(" | ")
                            .append(c.missingInC).append(" | ").append(c.extraInC).append(" |\n");
                }
                md.append("\nDelta-Zeitfenster ab ").append(since).append(" (").append(o.deltaDays)
                        .append(" Tage); erwartet = Seiten aus A mit version.when ≥ Fenster + 1 Tag (Zeitzonenpuffer).\n");
            }
            if (!subtrees.isEmpty()) {
                md.append("\n## Teilbaum (D)\n\n| Space | Wurzel | Nachfahren (B) | Anfragen B-Teilbaum | Anfragen CQL ancestor | fehlt in ancestor | ancestor zusätzlich | Kinder | Anfragen child/page | Anfragen CQL parent | fehlt in parent | parent zusätzlich |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n");
                for (Subtree s : subtrees) {
                    md.append("| ").append(s.space).append(" | ").append(s.root).append(" | ").append(s.size).append(" | ")
                            .append(s.treeRequests).append(" | ").append(s.ancestorRequests).append(" | ")
                            .append(s.missingInAncestor).append(" | ").append(s.extraInAncestor).append(" | ")
                            .append(s.children).append(" | ").append(s.childRequests).append(" | ")
                            .append(s.parentRequests).append(" | ").append(s.missingInParent).append(" | ")
                            .append(s.extraInParent).append(" |\n");
                }
            }
            if (!simulations.isEmpty()) {
                md.append("\n## Lokaler Merkle-Hash (E, nur lokale Simulation)\n\n| Space | Änderung | Tiefe | geänderte lokale Hashes | Anfragen zum Nachweis per Baum | per CQL-Delta | per flacher Inventur |\n|---|---|---|---|---|---|---|\n");
                for (Map.Entry<String, List<Simulation>> e : simulations.entrySet()) {
                    for (Simulation s : e.getValue()) {
                        md.append("| ").append(e.getKey()).append(" | ").append(s.change).append(" | ").append(s.depth)
                                .append(" | ").append(s.hashesChanged).append(" | ").append(s.detectedByTree).append(" | ")
                                .append(s.detectedByDelta).append(" | ").append(s.detectedByFlat).append(" |\n");
                    }
                }
                md.append("\nConfluence liefert keinen Teilbaum-Hash. Ein neuer Remote-Hash entsteht nur aus einem vollen Durchlauf; "
                        + "die lokalen Hashes zeigen, welche Knoten sich ändern, sparen aber keine Anfrage.\n");
            }
            if (!diffs.isEmpty()) {
                md.append("\n## Vergleich mit früherem Lauf (--compare)\n\n| Space | neu | entfernt | verschoben | bearbeitet | verschobene in CQL-Delta | verschobene mit neuer Version |\n|---|---|---|---|---|---|---|\n");
                for (Diff d : diffs) {
                    md.append("| ").append(d.space).append(" | ").append(d.added).append(" | ").append(d.removed)
                            .append(" | ").append(d.moved).append(" | ").append(d.edited).append(" | ")
                            .append(d.movedSeenByDelta).append(" | ").append(d.movedWithNewVersion).append(" |\n");
                }
                for (Diff d : diffs) {
                    for (String ex : d.examples) {
                        md.append("- ").append(d.space).append(": ").append(ex).append('\n');
                    }
                }
            }
            return md.toString();
        }

        static String shape(Snapshot s) {
            Map<String, Integer> kids = new HashMap<>();
            int roots = 0;
            for (Node n : s.nodes.values()) {
                if (n.parent == null) {
                    roots++;
                } else {
                    kids.merge(n.parent, 1, Integer::sum);
                }
            }
            int[] depths = s.nodes.keySet().stream().mapToInt(id -> depth(s.nodes, id)).sorted().toArray();
            int[] fan = kids.values().stream().mapToInt(Integer::intValue).sorted().toArray();
            return "Wurzelseiten " + roots + ", Elternknoten " + kids.size() + ", Blätter " + (s.nodes.size() - kids.size())
                    + ", Tiefe max " + (depths.length == 0 ? 0 : depths[depths.length - 1]) + " / Median "
                    + (depths.length == 0 ? 0 : depths[depths.length / 2]) + ", Kinder je Elternknoten max "
                    + (fan.length == 0 ? 0 : fan[fan.length - 1]) + " / Median " + (fan.length == 0 ? 0 : fan[fan.length / 2])
                    + "\n";
        }
    }

    static Instant parseWhen(String when) {
        if (when == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(when).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(when, DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss[.SSS]XX",
                        Locale.ROOT)).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------------------------------- Dateien

    static void writeCsv(Path file, List<Req> log) throws IOException {
        StringBuilder sb = new StringBuilder("lauf;space;verfahren;status;bytes;ms;content_type;pfad\n");
        for (Req r : log) {
            sb.append(r.run).append(';').append(r.space).append(';').append(r.method).append(';').append(r.status)
                    .append(';').append(r.bytes).append(';').append(r.millis).append(';').append(nz(r.contentType))
                    .append(';').append(r.path).append('\n');
        }
        Files.writeString(file, sb.toString());
    }

    static void writeSnapshot(Path file, Snapshot s) throws IOException {
        StringBuilder sb = new StringBuilder("{\"space\":").append(Json.quote(s.space)).append(",\"takenAt\":\"")
                .append(s.takenAt).append("\",\"complete\":").append(s.complete).append(",\"pages\":[\n");
        boolean first = true;
        for (Node n : s.nodes.values()) {
            sb.append(first ? "" : ",\n").append("{\"id\":").append(Json.quote(n.id)).append(",\"parent\":")
                    .append(n.parent == null ? "null" : Json.quote(n.parent)).append(",\"version\":").append(n.version)
                    .append(",\"when\":").append(n.when == null ? "null" : Json.quote(n.when)).append('}');
            first = false;
        }
        Files.writeString(file, sb.append("\n]}\n").toString());
    }

    static Snapshot readSnapshot(Path file) throws IOException {
        Map<String, Object> m = Json.obj(Json.parse(Files.readString(file)));
        Snapshot s = new Snapshot();
        s.space = (String) m.get("space");
        s.takenAt = Instant.parse((String) m.get("takenAt"));
        for (Object p : Json.arr(m.get("pages"))) {
            Map<String, Object> n = Json.obj(p);
            s.nodes.put((String) n.get("id"), new Node((String) n.get("id"), (String) n.get("parent"),
                    ((Number) n.get("version")).longValue(), (String) n.get("when")));
        }
        return s;
    }

    // ------------------------------------------------------------------------------------------- Hilfen

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    static String nz(String s) { return s == null ? "" : s; }

    static String safe(String s) { return s.replaceAll("[^A-Za-z0-9_-]", "_"); }

    static String stripSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Kleiner JSON-Leser ohne Abhängigkeiten: Map, List, String, Long/Double, Boolean, null. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String s) {
            Json j = new Json(s);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != s.length()) {
                throw new IllegalArgumentException("Restzeichen an Position " + j.i);
            }
            return v;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

        @SuppressWarnings("unchecked")
        static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : List.of(); }

        static String quote(String v) {
            StringBuilder sb = new StringBuilder("\"");
            for (char c : v.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.append('"').toString();
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private Object value() {
            char c = s.charAt(i);
            switch (c) {
                case '{': {
                    i++;
                    Map<String, Object> m = new LinkedHashMap<>();
                    ws();
                    if (s.charAt(i) == '}') { i++; return m; }
                    while (true) {
                        ws();
                        String k = string();
                        ws();
                        expect(':');
                        ws();
                        m.put(k, value());
                        ws();
                        if (s.charAt(i) == ',') { i++; continue; }
                        expect('}');
                        return m;
                    }
                }
                case '[': {
                    i++;
                    List<Object> l = new ArrayList<>();
                    ws();
                    if (s.charAt(i) == ']') { i++; return l; }
                    while (true) {
                        ws();
                        l.add(value());
                        ws();
                        if (s.charAt(i) == ',') { i++; continue; }
                        expect(']');
                        return l;
                    }
                }
                case '"':
                    return string();
                case 't':
                    i += 4;
                    return Boolean.TRUE;
                case 'f':
                    i += 5;
                    return Boolean.FALSE;
                case 'n':
                    i += 4;
                    return null;
                default: {
                    int start = i;
                    while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                        i++;
                    }
                    String num = s.substring(start, i);
                    if (num.isEmpty()) {
                        throw new IllegalArgumentException("Unerwartetes Zeichen '" + c + "' an Position " + start);
                    }
                    return num.matches("-?\\d+") ? (Object) Long.parseLong(num) : (Object) Double.parseDouble(num);
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private void expect(char c) {
            if (s.charAt(i) != c) {
                throw new IllegalArgumentException("'" + c + "' erwartet an Position " + i);
            }
            i++;
        }
    }

    // ------------------------------------------------------------------------------------------- Selbsttest

    /**
     * Simulierte Confluence (REST v1, nur die genutzten Endpunkte) mit einem zufälligen, aber festen Seitenbaum.
     * Prüft, dass A, B, C und D dieselben Mengen liefern, dass die Anfragezahlen den Formeln entsprechen, dass
     * --compare Verschiebungen erkennt und dass 429 den Lauf sofort stoppt.
     */
    static final class SelfTest {
        static final class FakePage {
            final String id;
            String parent;
            long version;
            Instant when;
            FakePage(String id, String parent, long version, Instant when) {
                this.id = id; this.parent = parent; this.version = version; this.when = when;
            }
        }

        final Map<String, FakePage> pages = new LinkedHashMap<>();
        final String space = "SIM";
        int rateLimitAfter = Integer.MAX_VALUE;
        int served;
        boolean moveTouchesLastModified = false;

        SelfTest(int size, long seed) {
            Random rnd = new Random(seed);
            Instant now = Instant.now();
            List<String> ids = new ArrayList<>();
            for (int n = 0; n < size; n++) {
                String id = String.valueOf(100000 + n);
                String parent = ids.isEmpty() || rnd.nextInt(10) == 0 ? null
                        : ids.get(Math.max(0, ids.size() - 1 - rnd.nextInt(Math.min(ids.size(), 30))));
                pages.put(id, new FakePage(id, parent, 1 + rnd.nextInt(20), now.minus(Duration.ofDays(rnd.nextInt(400)))));
                ids.add(id);
            }
        }

        HttpServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
            return server;
        }

        void handle(HttpExchange ex) throws IOException {
            served++;
            String path = ex.getRequestURI().getRawPath();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            if (served > rateLimitAfter) {
                send(ex, 429, "{\"message\":\"rate limited\"}", "application/json", Map.of("Retry-After", "60"));
                return;
            }
            int start = Integer.parseInt(q.getOrDefault("start", "0"));
            int limit = Integer.parseInt(q.getOrDefault("limit", "25"));
            String expand = q.getOrDefault("expand", "");
            List<FakePage> list;
            if (path.equals("/rest/applinks/1.0/manifest")) {
                send(ex, 200, "<manifest><version>9.2.0-sim</version></manifest>", "application/xml", Map.of());
                return;
            } else if (path.equals("/rest/api/space")) {
                String body = "{\"results\":[{\"key\":\"" + space + "\",\"type\":\"global\"}],\"start\":0,\"limit\":"
                        + limit + ",\"size\":1,\"_links\":{\"base\":\"http://x\",\"context\":\"\"}}";
                send(ex, 200, body, "application/json", Map.of());
                return;
            } else if (path.equals("/rest/api/content") && "trashed".equals(q.get("status"))) {
                send(ex, 403, "{\"message\":\"kein Space-Admin\"}", "application/json", Map.of());
                return;
            } else if (path.equals("/rest/api/content") && space.equals(q.get("spaceKey"))) {
                list = new ArrayList<>(pages.values());
            } else if (path.equals("/rest/api/space/" + space + "/content/page")) {
                list = pages.values().stream().filter(p -> p.parent == null).toList();
            } else if (path.matches("/rest/api/content/\\d+/child/page")) {
                String id = path.split("/")[4];
                list = pages.values().stream().filter(p -> id.equals(p.parent)).toList();
            } else if (path.matches("/rest/api/content/\\d+")) {
                FakePage p = pages.get(path.split("/")[4]);
                send(ex, 200, page(p, "version,ancestors,childTypes.page"), "application/json", Map.of());
                return;
            } else if (path.equals("/rest/api/content/search") || path.equals("/rest/api/search")) {
                list = cql(q.get("cql"));
                if (path.equals("/rest/api/search")) {
                    send(ex, 200, "{\"results\":[],\"start\":0,\"limit\":1,\"size\":0,\"totalSize\":" + list.size()
                            + "}", "application/json", Map.of());
                    return;
                }
            } else {
                send(ex, 404, "{\"message\":\"nicht simuliert: " + path + "\"}", "application/json", Map.of());
                return;
            }
            StringBuilder sb = new StringBuilder("{\"results\":[");
            int end = Math.min(list.size(), start + limit);
            for (int k = start; k < end; k++) {
                sb.append(k == start ? "" : ",").append(page(list.get(k), expand));
            }
            sb.append("],\"start\":").append(start).append(",\"limit\":").append(limit).append(",\"size\":")
                    .append(end - start).append(",\"_links\":{\"base\":\"http://x\",\"context\":\"\"");
            if (end < list.size()) {
                String raw = ex.getRequestURI().getRawQuery().replaceAll("(^|&)start=\\d+", "");
                sb.append(",\"next\":\"").append(path).append('?').append(raw.startsWith("&") ? raw.substring(1) : raw)
                        .append("&start=").append(end).append('"');
            }
            send(ex, 200, sb.append("}}").toString(), "application/json", Map.of());
        }

        List<FakePage> cql(String cql) {
            Matcher anc = Pattern.compile("ancestor = (\\d+)").matcher(cql);
            Matcher par = Pattern.compile("parent = (\\d+)").matcher(cql);
            Matcher lm = Pattern.compile("lastmodified >= \"([^\"]+)\"").matcher(cql);
            List<FakePage> out = new ArrayList<>();
            for (FakePage p : pages.values()) {
                if (anc.find(0) && !isAncestor(anc.group(1), p)) {
                    continue;
                }
                if (par.find(0) && !par.group(1).equals(p.parent)) {
                    continue;
                }
                if (lm.find(0) && p.when.isBefore(LocalDateTime.parse(lm.group(1), CQL_DATE).toInstant(ZoneOffset.UTC))) {
                    continue;
                }
                out.add(p);
            }
            return out;
        }

        boolean isAncestor(String id, FakePage p) {
            FakePage cur = p.parent == null ? null : pages.get(p.parent);
            while (cur != null) {
                if (cur.id.equals(id)) {
                    return true;
                }
                cur = cur.parent == null ? null : pages.get(cur.parent);
            }
            return false;
        }

        String page(FakePage p, String expand) {
            StringBuilder sb = new StringBuilder("{\"id\":\"").append(p.id).append("\",\"type\":\"page\",\"title\":\"S")
                    .append(p.id).append('"');
            if (expand.contains("version")) {
                sb.append(",\"version\":{\"number\":").append(p.version).append(",\"when\":\"")
                        .append(p.when.atOffset(ZoneOffset.UTC)).append("\"}");
            }
            if (expand.contains("ancestors")) {
                List<String> chain = new ArrayList<>();
                FakePage cur = p.parent == null ? null : pages.get(p.parent);
                while (cur != null) {
                    chain.add(0, "{\"id\":\"" + cur.id + "\"}");
                    cur = cur.parent == null ? null : pages.get(cur.parent);
                }
                sb.append(",\"ancestors\":[").append(String.join(",", chain)).append(']');
            }
            if (expand.contains("childTypes.page")) {
                boolean has = pages.values().stream().anyMatch(c -> p.id.equals(c.parent));
                sb.append(",\"childTypes\":{\"page\":{\"value\":").append(has).append("}}");
            }
            return sb.append('}').toString();
        }

        static Map<String, String> query(String raw) {
            Map<String, String> m = new HashMap<>();
            if (raw == null) {
                return m;
            }
            for (String part : raw.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    m.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
            return m;
        }

        static void send(HttpExchange ex, int status, String body, String type, Map<String, String> headers)
                throws IOException {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", type + ";charset=UTF-8");
            headers.forEach((k, v) -> ex.getResponseHeaders().set(k, v));
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        }

        static boolean run(Options base) throws Exception {
            boolean ok = true;
            for (int size : new int[] {40, 400, 2000}) {
                ok &= runOne(base, size);
            }
            ok &= rateLimitStops(base);
            ok &= fullRun();
            System.out.println(ok ? "SELBSTTEST GRÜN" : "SELBSTTEST ROT");
            return ok;
        }

        static boolean runOne(Options base, int size) throws Exception {
            SelfTest sim = new SelfTest(size, size);
            HttpServer server = sim.start();
            try {
                Options o = new Options();
                o.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
                o.limit = 25;
                o.delayMs = 0;
                o.maxRequestsPerMethod = 100000;
                o.maxRequestsTotal = 1000000;
                o.out = Files.createTempDirectory("benchmark-selbsttest");
                Http http = new Http(o);
                Report report = new Report(o);
                Instant since = Instant.now().minus(Duration.ofDays(30));
                MethodResult a = flat(http, o, "1", sim.space);
                MethodResult b = tree(http, o, "1", sim.space);
                Map<String, Integer> perParent = treeRequestsPerParent;
                MethodResult c = delta(http, o, "1", sim.space, since);
                report.compare(a, b, c, since);
                subtree(http, o, "1", sim.space, b, perParent, report);

                Map<String, Integer> kids = new HashMap<>();
                long roots = 0;
                for (FakePage p : sim.pages.values()) {
                    if (p.parent == null) {
                        roots++;
                    } else {
                        kids.merge(p.parent, 1, Integer::sum);
                    }
                }
                long expectTree = ceil(roots, o.limit);
                for (int k : kids.values()) {
                    expectTree += ceil(k, o.limit);
                }
                long expectFlat = Math.max(1, ceil(size, o.limit));
                long expectDelta = sim.pages.values().stream().filter(p -> !p.when.isBefore(
                        LocalDateTime.parse(CQL_DATE.format(since.atZone(ZoneOffset.UTC)), CQL_DATE).toInstant(ZoneOffset.UTC)))
                        .count();
                Report.Compare cmp = report.compares.get(0);
                List<String> fails = new ArrayList<>();
                check(fails, a.snapshot.nodes.size() == size, "A Seitenzahl " + a.snapshot.nodes.size());
                check(fails, cmp.onlyA == 0 && cmp.onlyB == 0 && cmp.parentMismatch == 0, "A/B Mengen " + cmp);
                check(fails, a.requests == expectFlat, "A Anfragen " + a.requests + " ≠ " + expectFlat);
                check(fails, b.requests == expectTree, "B Anfragen " + b.requests + " ≠ " + expectTree);
                check(fails, c.snapshot.nodes.size() == expectDelta, "C Seiten " + c.snapshot.nodes.size() + " ≠ " + expectDelta);
                check(fails, cmp.missingInC == 0 && cmp.extraInC == 0, "C Abdeckung " + cmp);
                check(fails, report.subtrees.size() == 1, "D nicht ausgeführt");
                for (Report.Subtree s : report.subtrees) {
                    check(fails, s.missingInAncestor == 0 && s.extraInAncestor == 0, "D ancestor " + s);
                    check(fails, s.missingInParent == 0 && s.extraInParent == 0, "D parent " + s);
                }

                // Verschiebung im Simulator: Space-Hash ändert sich, Delta sieht sie nicht (Version und Datum bleiben)
                Map<String, String> before = merkle(a.snapshot.nodes);
                FakePage leaf = sim.pages.values().stream().filter(p -> p.parent != null
                        && sim.pages.values().stream().noneMatch(x -> p.id.equals(x.parent))).findFirst().orElseThrow();
                String target = sim.pages.values().stream().filter(p -> !p.id.equals(leaf.id) && !p.id.equals(leaf.parent))
                        .findFirst().orElseThrow().id;
                leaf.parent = target;
                MethodResult a2 = flat(http, o, "2", sim.space);
                MethodResult c2 = delta(http, o, "2", sim.space, since);
                Diff d = diff(a.snapshot, a2.snapshot, c2.snapshot);
                check(fails, d.moved == 1 && d.added == 0 && d.removed == 0, "Vergleich " + d);
                check(fails, !before.get("").equals(merkle(a2.snapshot.nodes).get("")), "Space-Hash unverändert");
                List<Simulation> sims = simulate(a.snapshot.nodes, b.requests, c.requests, a.requests);
                check(fails, sims.size() == 4 && sims.stream().allMatch(s -> s.hashesChanged >= 2), "Simulation " + sims);

                System.out.printf(Locale.ROOT, "Selbsttest %4d Seiten: A %3d Anfragen, B %4d Anfragen (Elternknoten %d,"
                        + " Wurzeln %d), C %d Seiten → %s%n", size, a.requests, b.requests, kids.size(), roots,
                        c.snapshot.nodes.size(), fails.isEmpty() ? "ok" : fails);
                return fails.isEmpty();
            } finally {
                server.stop(0);
            }
        }

        static boolean rateLimitStops(Options base) throws Exception {
            SelfTest sim = new SelfTest(400, 7);
            sim.rateLimitAfter = 3;
            HttpServer server = sim.start();
            try {
                Options o = new Options();
                o.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
                o.limit = 25;
                o.delayMs = 0;
                Http http = new Http(o);
                try {
                    flat(http, o, "1", sim.space);
                    System.out.println("429-Test: kein Abbruch → FEHLER");
                    return false;
                } catch (Stop e) {
                    boolean ok = sim.served == 4 && http.log.size() == 4;
                    System.out.println("429-Test: " + e.getMessage() + " nach " + sim.served + " Anfragen → "
                            + (ok ? "ok" : "FEHLER"));
                    return ok;
                }
            } finally {
                server.stop(0);
            }
        }

        /** Der ganze Ablauf wie gegen eine echte Instanz: Phase 0, Space-Wahl, Kalt-/Warmlauf, Bericht, --compare. */
        static boolean fullRun() throws Exception {
            SelfTest sim = new SelfTest(300, 3);
            HttpServer server = sim.start();
            try {
                Options o = new Options();
                o.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
                o.limit = 25;
                o.delayMs = 0;
                o.repeat = 2;
                o.out = Files.createTempDirectory("benchmark-gesamt");
                Report first = execute(o);
                Path snap = o.out.resolve("snapshot-SIM.json");
                FakePage leaf = sim.pages.values().stream().filter(p -> p.parent != null
                        && sim.pages.values().stream().noneMatch(x -> p.id.equals(x.parent))).findFirst().orElseThrow();
                leaf.parent = null;
                Options o2 = new Options();
                o2.baseUrl = o.baseUrl;
                o2.limit = 25;
                o2.delayMs = 0;
                o2.compare = Files.copy(snap, o.out.resolve("vorher.json")).toString();
                o2.out = o.out.resolve("zweiter-lauf");
                Report second = execute(o2);
                String md = Files.readString(o2.out.resolve("bericht.md"));
                boolean ok = first.gate && first.stopped == null && first.results.size() == 9
                        && second.diffs.size() == 1 && second.diffs.get(0).moved() == 1
                        && md.contains("## Vergleich mit früherem Lauf") && Files.exists(o.out.resolve("anfragen.csv"));
                System.out.println("Gesamtablauf: Bericht " + o.out.resolve("bericht.md") + " → " + (ok ? "ok" : "FEHLER"));
                return ok;
            } finally {
                server.stop(0);
            }
        }

        static long ceil(long a, long b) { return (a + b - 1) / b; }

        static void check(List<String> fails, boolean ok, String what) {
            if (!ok) {
                fails.add(what);
            }
        }
    }
}
