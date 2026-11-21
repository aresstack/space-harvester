package com.aresstack.spaceharvester.source.confluence;

import com.aresstack.spaceharvester.application.links.LinkTarget;
import com.aresstack.spaceharvester.application.links.PageLinkExtractor;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verweisziele einer gespeicherten Confluence-Seite aus {@code body.storage} (Paket V1), ohne Netz. Der
 * Confluence-Anschluss liest dieselben Ziele live aus der gerenderten Ansicht ({@code body.view}, {@code
 * ConfluencePageLinks}); die gespeicherte Payload enthält nur das Storage-Format, darum liest dieser Extraktor dessen
 * Gegenstücke:
 *
 * <ul>
 *   <li>{@code <ac:link><ri:page ri:content-title="…" [ri:space-key="…"]/>…</ac:link>}: Space und Titel (ohne Space
 *   der eigene), mit {@code ri:content-id} die ID,</li>
 *   <li>{@code <a href="…">} auf diese Confluence in den Formen des Anschlusses ({@code ConfluenceLinks}):
 *   {@code pages/viewpage.action?pageId=}, {@code spaces/KEY/pages/ID}, {@code x/Kurzcode} (ID),
 *   {@code display/KEY/Titel} und {@code viewpage.action?spaceKey=&title=} (Space und Titel).</li>
 * </ul>
 *
 * Nicht dazu gehören wie beim Anschluss: Anhänge (auch fremder Seiten, {@code ri:attachment}), Blogbeiträge, Benutzer,
 * Bilder, Anker der eigenen Seite und die Seite selbst. Anders als die gerenderte Ansicht zählen Seiten in
 * Makroparametern ({@code ac:parameter}, etwa Include) nicht, denn sie erscheinen dort nicht als Link; Verweise auf die
 * Startseite eines Space ({@code display/KEY}, {@code ri:space}) nennen keine Seite und entfallen. Die Basis dieser
 * Confluence steht im Locator der Ressourcen-ID ({@code confluence:<host/kontext>/page/<id>}). CDATA (Code-Makros) und
 * Kommentare werden übersprungen; der Leser baut kein DOM und ist fehlertolerant.
 */
public final class ConfluenceStorageLinks implements PageLinkExtractor {

    private static final String SCHEME = "confluence:";
    private static final String PAGE_PATH = "/page/";
    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z][\\w:.-]*)((?:\"[^\"]*\"|'[^']*'|[^'\">])*)>");
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w:.-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern CONTENT_ID = Pattern.compile("[0-9]{1,19}");
    private static final Pattern SPACE_KEY = Pattern.compile("~?[A-Za-z0-9_][A-Za-z0-9_.@-]{0,254}");
    private static final Pattern SPACES_PAGE = Pattern.compile("/spaces/[^/]+/pages/([0-9]{1,19})(?:/.*)?");
    private static final Pattern DISPLAY = Pattern.compile("/display/([^/]+)(?:/([^/]*))?/?");
    private static final Pattern TINY = Pattern.compile("/x/([A-Za-z0-9_-]{1,11})/?");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9A-Fa-f]{1,6});");
    private static final int TINY_LENGTH = 11;

    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public boolean supports(PageMetadata metadata) {
        String resourceId = metadata.resourceId();
        return resourceId.startsWith(SCHEME) && resourceId.lastIndexOf(PAGE_PATH) > SCHEME.length();
    }

    @Override
    public List<LinkTarget> targets(PageSnapshot snapshot) {
        String format = snapshot.payload().nativeFormat();
        String prefix = ConfluenceStorageTextProjector.NATIVE_FORMAT_PREFIX;
        if (!format.equals(prefix) && !format.startsWith(prefix + ";")) {
            throw new IllegalArgumentException("Payload ist kein Confluence-Inhalt");
        }
        if (!supports(snapshot.metadata())) {
            throw new IllegalArgumentException("Keine Confluence-Seite");
        }
        JsonNode root;
        try {
            root = json.readTree(snapshot.payload().bytes());
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Payload ist kein JSON");
        }
        JsonNode value = root == null ? null : root.path("body").path("storage").path("value");
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException("Payload enthält kein body.storage.value");
        }
        PageMetadata metadata = snapshot.metadata();
        String resourceId = metadata.resourceId();
        String locator = resourceId.substring(SCHEME.length(), resourceId.lastIndexOf(PAGE_PATH));
        return of(value.asString(), locator, metadata.pageId(), metadata.partitionKey(), metadata.title());
    }

    /**
     * Die Ziele eines Storage-Inhalts.
     *
     * @param locator Basis-URL der Confluence ohne Schema, etwa {@code cf.intern/confluence}
     */
    static List<LinkTarget> of(String storage, String locator, String pageId, String spaceKey, String title) {
        Map<String, LinkTarget> targets = new LinkedHashMap<>();
        String ownTitle = title == null || title.isBlank() ? null : LinkTarget.titleKey(spaceKey, title);
        int links = 0;
        int parameters = 0;
        int attachments = 0;
        int images = 0;
        int i = 0;
        int length = storage.length();
        while (i < length) {
            int lt = storage.indexOf('<', i);
            if (lt < 0) {
                break;
            }
            if (storage.startsWith("<![CDATA[", lt)) {
                int end = storage.indexOf("]]>", lt + 9);
                i = end < 0 ? length : end + 3;
                continue;
            }
            if (storage.startsWith("<!--", lt)) {
                int end = storage.indexOf("-->", lt + 4);
                i = end < 0 ? length : end + 3;
                continue;
            }
            Matcher tag = TAG.matcher(storage);
            tag.region(lt, length);
            if (!tag.lookingAt()) {
                i = lt + 1;
                continue;
            }
            i = tag.end();
            boolean closing = !tag.group(1).isEmpty();
            String name = tag.group(2).toLowerCase(Locale.ROOT);
            String rest = tag.group(3);
            boolean selfClosing = rest.endsWith("/");
            int delta = closing ? -1 : selfClosing ? 0 : 1;
            switch (name) {
                case "ac:link" -> links = Math.max(0, links + delta);
                case "ac:parameter" -> parameters = Math.max(0, parameters + delta);
                case "ri:attachment" -> attachments = Math.max(0, attachments + delta);
                case "ac:image" -> images = Math.max(0, images + delta);
                default -> {
                    // andere Elemente zählen nicht
                }
            }
            if (closing) {
                continue;
            }
            LinkTarget target = null;
            if ("ri:page".equals(name) && links > 0 && parameters == 0 && attachments == 0 && images == 0) {
                target = page(attributes(rest), spaceKey);
            } else if ("a".equals(name) && parameters == 0) {
                String href = attributes(rest).get("href");
                target = href == null ? null : href(href.strip(), locator);
            }
            if (target == null) {
                continue;
            }
            if (target.byId() ? target.pageId().equals(pageId) : target.titleKey().equals(ownTitle)) {
                continue;
            }
            targets.putIfAbsent(target.byId() ? "id:" + target.pageId() : "title:" + target.titleKey(), target);
        }
        return new ArrayList<>(targets.values());
    }

    private static LinkTarget page(Map<String, String> attributes, String ownSpace) {
        String contentId = attributes.get("ri:content-id");
        if (contentId != null && CONTENT_ID.matcher(contentId).matches()) {
            return LinkTarget.page(contentId);
        }
        String title = attributes.get("ri:content-title");
        if (title == null || title.isBlank()) {
            return null; // Anker der eigenen Seite
        }
        String space = attributes.getOrDefault("ri:space-key", ownSpace);
        return space == null || space.isBlank() ? null : LinkTarget.titled(space, title);
    }

    /** Die Seite hinter einer Adresse dieser Confluence oder {@code null}, wie {@code ConfluenceLinks.parse}. */
    static LinkTarget href(String href, String locator) {
        String rest = underBase(href, locator);
        if (rest == null) {
            return null;
        }
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            rest = rest.substring(0, hash);
        }
        int question = rest.indexOf('?');
        String path = question < 0 ? rest : rest.substring(0, question);
        String query = question < 0 ? "" : rest.substring(question + 1);
        if ("/pages/viewpage.action".equals(path)) {
            String pageId = parameter(query, "pageId");
            if (pageId != null) {
                return CONTENT_ID.matcher(pageId).matches() ? LinkTarget.page(pageId) : null;
            }
            return titled(parameter(query, "spaceKey"), parameter(query, "title"));
        }
        Matcher spacesPage = SPACES_PAGE.matcher(path);
        if (spacesPage.matches()) {
            return LinkTarget.page(spacesPage.group(1));
        }
        Matcher tiny = TINY.matcher(path);
        if (tiny.matches()) {
            String pageId = tinyId(tiny.group(1));
            return pageId == null ? null : LinkTarget.page(pageId);
        }
        Matcher display = DISPLAY.matcher(path);
        if (display.matches() && query.isEmpty() && display.group(2) != null) {
            return titled(decode(display.group(1)), decode(display.group(2)));
        }
        return null;
    }

    /**
     * Der Pfad hinter der Basis oder {@code null}, wenn die Adresse nicht zu dieser Confluence gehört: absolut mit
     * gleichem Host (ohne Groß-/Kleinschreibung, Standardport egal) und Kontextpfad, oder relativ zum Host mit
     * Kontextpfad.
     */
    private static String underBase(String href, String locator) {
        int slash = locator.indexOf('/');
        String host = withoutDefaultPort(slash < 0 ? locator : locator.substring(0, slash));
        String context = slash < 0 ? "" : locator.substring(slash);
        if (context.endsWith("/")) {
            context = context.substring(0, context.length() - 1);
        }
        String path;
        String lower = href.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            String withoutScheme = href.substring(href.indexOf("://") + 3);
            int end = firstOf(withoutScheme, "/?#");
            String hrefHost = withoutDefaultPort(withoutScheme.substring(0, end));
            if (!hrefHost.equalsIgnoreCase(host)) {
                return null;
            }
            path = withoutScheme.substring(end);
        } else if (href.startsWith("/") && !href.startsWith("//")) {
            path = href;
        } else {
            return null;
        }
        if (context.isEmpty()) {
            return path;
        }
        if (!path.startsWith(context)) {
            return null;
        }
        String rest = path.substring(context.length());
        return rest.isEmpty() || "/?#".indexOf(rest.charAt(0)) >= 0 ? rest : null;
    }

    private static String withoutDefaultPort(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.endsWith(":80")) {
            return host.substring(0, host.length() - 3);
        }
        if (lower.endsWith(":443")) {
            return host.substring(0, host.length() - 4);
        }
        return host;
    }

    private static int firstOf(String value, String characters) {
        for (int i = 0; i < value.length(); i++) {
            if (characters.indexOf(value.charAt(i)) >= 0) {
                return i;
            }
        }
        return value.length();
    }

    private static LinkTarget titled(String spaceKey, String title) {
        if (spaceKey == null || !SPACE_KEY.matcher(spaceKey).matches() || title == null || title.isBlank()) {
            return null;
        }
        return LinkTarget.titled(spaceKey, title);
    }

    private static String parameter(String query, String name) {
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return decode(pair.substring(equals + 1));
            }
        }
        return null;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Wie {@code ConfluenceLinks.tinyId}: acht Bytes Little Endian in Base64 mit {@code -} und {@code _}. */
    static String tinyId(String code) {
        StringBuilder padded = new StringBuilder(code.replace('-', '/').replace('_', '+'));
        while (padded.length() < TINY_LENGTH) {
            padded.append('A');
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(padded.append('=').toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
        long id = 0;
        for (int i = bytes.length - 1; i >= 0; i--) {
            id = (id << 8) | (bytes[i] & 0xFF);
        }
        return id > 0 ? Long.toString(id) : null;
    }

    private static Map<String, String> attributes(String rest) {
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(rest);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            attributes.putIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), unescape(value));
        }
        return attributes;
    }

    private static String unescape(String value) {
        if (value.indexOf('&') < 0) {
            return value;
        }
        Matcher numeric = NUMERIC_ENTITY.matcher(value);
        StringBuilder decoded = new StringBuilder();
        while (numeric.find()) {
            int code;
            try {
                code = Integer.parseInt(numeric.group(2), numeric.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException e) {
                code = -1;
            }
            String replacement = code > 0 && Character.isValidCodePoint(code) ? Character.toString(code)
                    : numeric.group();
            numeric.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
        }
        numeric.appendTail(decoded);
        return decoded.toString().replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&");
    }
}
