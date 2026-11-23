package com.aresstack.spaceharvester.deck.files.sharepoint;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * SharePoint-Adressen und WebDAV-UNC-Pfade, übernommen aus {@code SharePointPathUtil} von MainframeMate. Windows macht
 * jede Dokumentbibliothek über den Dienst „WebClient“ als UNC-Pfad erreichbar:
 * <pre>
 *   https://host/pfad  →  \\host@SSL\DavWWWRoot\pfad
 *   http://host/pfad   →  \\host\DavWWWRoot\pfad
 * </pre>
 */
public final class SharePointPaths {

    private static final String ROOT = "\\DavWWWRoot";

    private SharePointPaths() {
    }

    /** Ob {@code text} eine Web-Adresse (http/https) ist. */
    public static boolean isWebUrl(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.strip().toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    /** Ob {@code path} ein WebDAV-UNC-Pfad ist. */
    public static boolean isWebDav(String path) {
        return path != null && path.startsWith("\\\\") && path.toLowerCase(Locale.ROOT).contains("\\davwwwroot");
    }

    /** Web-Adresse → UNC-Pfad; leer, wenn es keine gültige Web-Adresse ist. */
    public static Optional<String> toUncPath(String webUrl) {
        if (!isWebUrl(webUrl)) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(webUrl.strip().replace(" ", "%20"));
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return Optional.empty();
            }
            StringBuilder unc = new StringBuilder("\\\\").append(host);
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                unc.append("@SSL");
            }
            int port = uri.getPort();
            if (port > 0 && port != 80 && port != 443) {
                unc.append('@').append(port);
            }
            unc.append(ROOT);
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                String decoded = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
                while (decoded.endsWith("/")) {
                    decoded = decoded.substring(0, decoded.length() - 1);
                }
                unc.append(decoded.replace('/', '\\'));
            }
            return Optional.of(unc.toString());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** UNC-Pfad → Web-Adresse (bestmöglich, wie in MainframeMate). */
    public static String toWebUrl(String uncPath) {
        if (uncPath == null || !uncPath.startsWith("\\\\")) {
            return uncPath;
        }
        String rest = uncPath.substring(2);
        int slash = rest.indexOf('\\');
        if (slash < 0) {
            return uncPath;
        }
        String hostPart = rest.substring(0, slash);
        String path = rest.substring(slash);
        boolean ssl = hostPart.toUpperCase(Locale.ROOT).contains("@SSL");
        String host = hostPart.replaceAll("(?i)@SSL", "");
        String port = "";
        int at = host.indexOf('@');
        if (at > 0) {
            port = ":" + host.substring(at + 1);
            host = host.substring(0, at);
        }
        if (path.toLowerCase(Locale.ROOT).startsWith(ROOT.toLowerCase(Locale.ROOT))) {
            path = path.substring(ROOT.length());
        }
        return (ssl ? "https://" : "http://") + host + port + path.replace('\\', '/');
    }

    /** Der Rechnername eines UNC-Pfads samt {@code @SSL}, für die Anmeldung je Rechner. */
    public static Optional<String> host(String uncPath) {
        if (uncPath == null || !uncPath.startsWith("\\\\")) {
            return Optional.empty();
        }
        String rest = uncPath.substring(2);
        int slash = rest.indexOf('\\');
        return Optional.of(slash < 0 ? rest : rest.substring(0, slash));
    }

    /** Anzeigename: Site-Name aus {@code /sites/<Name>}, sonst Rechnername (wie MainframeMate). */
    public static String displayName(String urlOrPath) {
        if (urlOrPath == null || urlOrPath.isBlank()) {
            return "?";
        }
        String lower = urlOrPath.toLowerCase(Locale.ROOT);
        int sites = lower.indexOf("/sites/");
        if (sites < 0) {
            sites = lower.indexOf("\\sites\\");
        }
        if (sites >= 0) {
            String rest = urlOrPath.substring(sites + 7);
            int end = rest.indexOf('/');
            if (end < 0) {
                end = rest.indexOf('\\');
            }
            if (end < 0) {
                end = rest.length();
            }
            return rest.substring(0, end).replace("%20", " ").replace("-", " ");
        }
        if (isWebUrl(urlOrPath)) {
            try {
                String host = URI.create(urlOrPath.strip().replace(" ", "%20")).getHost();
                if (host != null) {
                    return host;
                }
            } catch (IllegalArgumentException ignored) {
                // unten
            }
        }
        return urlOrPath.length() > 30 ? urlOrPath.substring(0, 30) + "…" : urlOrPath;
    }
}
