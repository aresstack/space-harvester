package com.aresstack.spaceharvester.source.network;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.spaceharvester.source.confluence.WebClientConfluenceTransport;
import java.io.PrintStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Proxy-Diagnose ohne Start des Dienstes: löst für jede Ziel-URL die Route mit denselben Einstellungen wie
 * {@code space-harvester.network.proxy} auf und zeigt alle Schritte der Bibliothek. Läuft auch aus dem ausführbaren
 * JAR (und prüft damit, dass GraalJS dort verfügbar ist):
 *
 * <pre>
 * java -Dloader.main=com.aresstack.spaceharvester.source.network.ProxyDiagnosticsMain \
 *      -cp space-harvester.jar org.springframework.boot.loader.launch.PropertiesLauncher \
 *      --mode=pac-url-powershell https://confluence.example.org/
 * </pre>
 *
 * Optionen: {@code --mode}, {@code --pac-url}, {@code --proxy-host}, {@code --proxy-port},
 * {@code --non-proxy-hosts} (kommagetrennt), {@code --resolve-timeout} (z. B. {@code 20s}), {@code --fetch=async}
 * (jede Ziel-URL zusätzlich ohne Anmeldung über den asynchronen Transport abrufen, #47; nur Status und Größe
 * werden ausgegeben). Exit-Code 0, wenn jede Route bestimmbar ist, 2 bei einer nicht bestimmbaren Route, 3 bei
 * einem fehlgeschlagenen Abruf, 1 bei falschen Argumenten.
 */
public final class ProxyDiagnosticsMain {

    private ProxyDiagnosticsMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        ProxySettings.Mode mode = ProxySettings.Mode.DISABLED;
        URI pacUrl = null;
        String proxyHost = null;
        Integer proxyPort = null;
        List<String> nonProxyHosts = List.of();
        Duration timeout = null;
        boolean fetch = false;
        List<URI> targets = new ArrayList<>();
        try {
            for (String arg : args) {
                if (!arg.startsWith("--")) {
                    targets.add(URI.create(arg));
                    continue;
                }
                int eq = arg.indexOf('=');
                if (eq < 0) {
                    throw new IllegalArgumentException("Option ohne Wert: " + arg);
                }
                String name = arg.substring(2, eq);
                String value = arg.substring(eq + 1);
                switch (name) {
                    case "mode" -> mode = ProxySettings.Mode.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
                    case "pac-url" -> pacUrl = URI.create(value);
                    case "proxy-host" -> proxyHost = value;
                    case "proxy-port" -> proxyPort = Integer.valueOf(value);
                    case "non-proxy-hosts" -> nonProxyHosts = Arrays.stream(value.split(",")).map(String::trim)
                            .filter(host -> !host.isEmpty()).toList();
                    case "resolve-timeout" -> timeout = Duration.parse("PT" + value.toUpperCase(Locale.ROOT));
                    case "fetch" -> {
                        if (!value.equals("async")) {
                            throw new IllegalArgumentException("--fetch kennt nur async");
                        }
                        fetch = true;
                    }
                    default -> throw new IllegalArgumentException("Unbekannte Option: --" + name);
                }
            }
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("mindestens eine Ziel-URL angeben");
            }
        } catch (RuntimeException e) {
            err.println("Fehler: " + e.getMessage());
            err.println("Aufruf: [--mode=...] [--pac-url=...] [--proxy-host=... --proxy-port=...] "
                    + "[--non-proxy-hosts=a,*.b] [--resolve-timeout=20s] [--fetch=async] <ziel-url>...");
            return 1;
        }
        ProxyRoutes routes;
        try {
            routes = ProxyRoutes.from(new ProxySettings(mode, pacUrl, proxyHost, proxyPort, nonProxyHosts, timeout,
                    null, null));
        } catch (InvalidNetworkConfigurationException e) {
            err.println(e.getMessage());
            return 1;
        }
        out.println("Proxy: " + routes.describe());
        boolean allResolved = true;
        for (URI target : targets) {
            List<String> lines = routes.diagnose(target);
            lines.forEach(out::println);
            allResolved &= !lines.get(lines.size() - 1).startsWith("Result: UNAVAILABLE");
        }
        if (!allResolved) {
            return 2;
        }
        return fetch && !fetchAll(routes, targets, out) ? 3 : 0;
    }

    /** Ruft jedes Ziel ohne Anmeldung asynchron ab und gibt nur Status und Größe aus, nie den Inhalt. */
    private static boolean fetchAll(ProxyRoutes routes, List<URI> targets, PrintStream out) {
        boolean ok = true;
        try (WebClientConfluenceTransport transport = WebClientConfluenceTransport.builder().routes(routes).build()) {
            for (URI target : targets) {
                try {
                    ConfluenceHttpResponse response = transport.getAnonymousAsync(target, Map.of(), 1 << 20)
                            .toCompletableFuture().get(60, TimeUnit.SECONDS);
                    out.println("Fetch " + ProxyRoutes.displayUrl(target.toString()) + ": HTTP " + response.status()
                            + ", " + response.length() + " Bytes");
                } catch (ExecutionException e) {
                    ok = false;
                    out.println("Fetch " + ProxyRoutes.displayUrl(target.toString()) + " fehlgeschlagen: "
                            + e.getCause().getClass().getSimpleName()
                            + (e.getCause().getMessage() == null ? "" : ": " + e.getCause().getMessage()));
                } catch (InterruptedException | TimeoutException e) {
                    ok = false;
                    out.println("Fetch " + ProxyRoutes.displayUrl(target.toString()) + " fehlgeschlagen: "
                            + e.getClass().getSimpleName());
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        return ok;
    }
}
