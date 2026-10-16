package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bereitet {@code body.view} für die Lese-Ansicht auf (paketintern), mit den Regeln aus MainframeMate
 * {@code HtmlImageExtractor}/{@code ConfluenceReaderTab}: Bilder bleiben an ihrer Stelle, die Bildliste folgt der
 * Dokumentreihenfolge (jede Adresse einmal). {@code data:}-Bilder, Emoticons, Icons und Jira-Symbole sind keine
 * Inhaltsbilder: sie fallen aus dem HTML (ein Emoticon bleibt als sein Alternativtext), siehe
 * {@link #isDecorative}. Versteckte
 * Elemente ({@code .hidden}, {@code display:none}) fallen weg wie in {@link ConfluenceHtmlText}. Anders als
 * dort liest jsoup die Attribute; ist das Bild ein Anhang ({@code data-linked-resource-id}), kommen Anhang-ID,
 * Version und Medientyp mit. Entfernt werden Skripte, Formulare und eingebettete Objekte; Überschriften ohne
 * {@code id} bekommen eine aus ihrem Text (MainframeMate {@code injectHeadingAnchors}).
 */
final class ConfluencePageView {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(ConfluencePageView.class.getName());

    final String html;
    final List<InlineImage> images;

    private ConfluencePageView(String html, List<InlineImage> images) {
        this.html = html;
        this.images = images;
    }

    private static final Pattern JIRA_PLACEHOLDER =
            Pattern.compile("(?i)^\\s*Getting issue details\\s*(\\.\\.\\.|\u2026)?\\s*$");

    /**
     * Jira-Makros, die Confluence nur im Browser per Script füllt ({@code body.view}, Rückfall ohne
     * {@code export_view}): „KEY - Getting issue details... STATUS“ wird zum Vorgangsschlüssel mit seinem Link,
     * ein Platzhalter ohne Vorgang fällt weg.
     */
    static void dropJiraPlaceholders(Document doc) {
        for (Element placeholder : doc.body().getElementsMatchingOwnText(JIRA_PLACEHOLDER)) {
            if (placeholder.parent() == null) {
                continue; // schon mit seinem Vorgang ersetzt
            }
            Element issue = placeholder.closest(".jira-issue, [data-jira-key]");
            if (issue == null) {
                placeholder.remove();
                continue;
            }
            Element key = issue.selectFirst("a.jira-issue-key, a[href*=/browse/]");
            issue.empty();
            if (key != null) {
                issue.appendChild(key);
            } else if (!issue.attr("data-jira-key").isEmpty()) {
                issue.appendText(issue.attr("data-jira-key"));
            }
        }
    }

    /** @param baseUri Basis-URL der Confluence-Instanz mit abschließendem {@code /} */
    static ConfluencePageView of(String bodyHtml, String baseUri) {
        return of(bodyHtml, baseUri, null);
    }

    /**
     * @param baseUri    eingestellte Basis-URL mit abschließendem {@code /}
     * @param serverBase {@code _links.base} der Antwort (die Basis, die Confluence selbst für sich eingestellt hat,
     *                   etwa anderer Hostname, Schema oder Port) oder {@code null}; Bildadressen darunter gehören zu
     *                   derselben Confluence und werden auf {@code baseUri} umgeschrieben (Pfad hinter dem
     *                   Kontextpfad und Query bleiben), damit sie über die eingestellte Adresse angemeldet laden.
     *                   Bilder anderer Hosts bleiben fremd.
     */
    static ConfluencePageView of(String bodyHtml, String baseUri, String serverBase) {
        return of(bodyHtml, baseUri, serverBase, null, null);
    }

    /**
     * @param storage {@code body.storage} der Seite oder {@code null}: draw.io-Makros mit Name und Revision, falls
     *                {@code export_view} nur das Vorschaubild ohne {@code data-diagramdata} liefert
     * @param pageId  ID der Seite (Eigentümer der Diagramme ohne eigenes {@code pageId}) oder {@code null}
     */
    static ConfluencePageView of(String bodyHtml, String baseUri, String serverBase, String storage, String pageId) {
        Document doc = Jsoup.parseBodyFragment(bodyHtml == null ? "" : bodyHtml, baseUri);
        // vor dem Entfernen versteckter Elemente: die Diagrammdaten sind versteckt
        int fromData = drawioPreviews(doc, baseUri);
        int fromStorage = storageDrawioPreviews(doc, baseUri, storage, pageId);
        if (fromData + fromStorage > 0 || (storage != null && storage.contains("ac:name=\"drawio"))
                || (storage != null && storage.contains("ac:name=\"inc-drawio"))) {
            LOG.info("[Confluence] draw.io: Seite " + pageId + ": Bilder gekennzeichnet aus Diagrammdaten=" + fromData
                    + ", aus Makroparametern=" + fromStorage + ", Makros im Seitenquelltext="
                    + (storage == null ? "unbekannt" : String.valueOf(storageMacros(storage).size())));
        }
        doc.select("script, noscript, iframe, object, embed, applet, form, input, button, select, textarea").remove();
        doc.select("style, svg").remove();
        // Wie ConfluenceHtmlText: Versteckte Lade-/Platzhalterzeilen und Bedienelemente nicht zeigen; Swings
        // HTML-Darstellung kennt display:none nicht zuverlässig.
        doc.select(".expand-control, .aui-icon, .hidden").remove();
        doc.select("[style~=(?i)display:\\s*none]").remove();
        dropJiraPlaceholders(doc);
        List<InlineImage> images = new ArrayList<InlineImage>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Element img : doc.select("img")) {
            img.removeAttr("srcset");
            String raw = img.attr("src");
            String src = img.absUrl("src");
            if (src.isEmpty()) {
                src = raw;
            }
            src = ConfluenceRestClient.encodeUnsafe(src);
            src = onBase(onBase(src, serverBase, baseUri, true), baseUri, baseUri, false);
            img.attr("src", src);
            if (isDecorative(img, raw, src)) {
                dropDecorative(img, src);
                continue;
            }
            if (!seen.add(src)) {
                continue;
            }
            String linkedType = img.attr("data-linked-resource-type").trim();
            String attachmentId = linkedType.isEmpty() || "attachment".equalsIgnoreCase(linkedType)
                    ? attachmentId(img.attr("data-linked-resource-id").trim()) : null;
            String title = img.attr("alt").trim();
            if (title.isEmpty()) {
                title = img.attr("title").trim();
            }
            if (title.isEmpty()) {
                title = img.attr("data-linked-resource-default-alias").trim();
            }
            images.add(new InlineImage(src, attachmentId, title,
                    img.attr("data-linked-resource-version").trim(),
                    img.attr("data-linked-resource-content-type").trim(),
                    img.attr("data-linked-resource-container-id").trim()));
        }
        pageLinks(doc, baseUri, serverBase);
        Set<String> ids = new LinkedHashSet<String>();
        for (Element heading : doc.select("h1, h2, h3, h4, h5, h6")) {
            if (!heading.id().isEmpty()) {
                ids.add(heading.id());
                continue;
            }
            String anchor = heading.text().trim().replaceAll("[^\\p{L}\\p{N}]+", "-").toLowerCase(Locale.ROOT);
            if (anchor.isEmpty()) {
                continue;
            }
            String unique = anchor;
            for (int n = 2; !ids.add(unique); n++) {
                unique = anchor + "-" + n;
            }
            heading.attr("id", unique);
        }
        doc.outputSettings().prettyPrint(false);
        return new ConfluencePageView(doc.body().html(), images);
    }

    /**
     * Ein Symbol statt eines Inhaltsbilds: {@code data:}-Bilder, Confluence-Emoticons und -Icons, Jira-Avatare
     * ({@code /secure/viewavatar}), Bilder mit Klasse {@code icon}/{@code emoticon} und alles innerhalb eines
     * Jira-Vorgangs ({@code .jira-issue}, {@code [data-jira-key]}, {@code a.jira-issue-key}). Solche Bilder kommen
     * weder in die Bildliste noch bleiben sie im HTML: die Lese-Ansicht hätte für sie nur einen Platzhalter.
     */
    static boolean isDecorative(Element img, String raw, String src) {
        if (img.hasAttr(DRAWIO_MARK)) {
            return false; // ein draw.io-Diagramm ist immer Inhalt, gleich wie Confluence sein img auszeichnet
        }
        if (raw.startsWith("data:") || src.contains("/emoticons/") || src.contains("/icons/")
                || src.contains("/secure/viewavatar") || img.hasClass("icon") || img.hasClass("emoticon")
                || img.hasAttr("data-emoticon-name")) {
            return true;
        }
        return img.closest(".jira-issue, [data-jira-key], a.jira-issue-key") != null;
    }

    /**
     * Entfernt ein Symbol aus dem HTML; ein Emoticon mit Alternativtext (etwa {@code (/)} als Haken in einer Tabelle)
     * bleibt als dieser Text stehen. Ein umgebender Link bleibt unverändert.
     */
    private static void dropDecorative(Element img, String src) {
        String alt = img.attr("alt").trim();
        boolean emoticon = src.contains("/emoticons/") || img.hasClass("emoticon") || img.hasAttr("data-emoticon-name");
        if (emoticon && !alt.isEmpty()) {
            img.replaceWith(new TextNode(alt));
        } else {
            img.remove();
        }
    }

    /**
     * draw.io-Diagramme sind kein {@code <img>}: Der Browser zeichnet sie per Script aus den Diagrammdaten
     * ({@code /rest/drawio/...}), im HTML steht nur ein Platzhalter mit verstecktem {@code data-diagramdata}
     * (Base64-JSON). Dieses nennt die Vorschaudatei, die das Plugin als Anhang der Seite ablegt
     * ({@code previewName}, {@code owningPageId}, {@code revision}; bei {@code inc-drawio} eine fremde Seite). Für jeden Platzhalter ohne eigenes Bild wird diese Vorschau als
     * gewöhnliches Bild nach dem Makro eingefügt; sie lädt dann angemeldet wie jeder Anhang. Der Fragmentteil der
     * Adresse nennt dem Bildlader Diagrammname, Seite und Revision, damit er das Diagramm selbst zeichnen kann
     * ({@link DrawioDiagramRenderer}) und nur im Zweifel die Vorschau nimmt.
     */
    /** Fragmentschlüssel an der Vorschauadresse eines draw.io-Diagramms: {@code #drawio=<Name>&page=<Id>&revision=<n>}. */
    static final String DRAWIO_FRAGMENT = "drawio";
    /**
     * Zusatz am draw.io-Fragment für den Umschalter „Original“ der Oberfläche: nur die PNG-Vorschau laden
     * ({@code …#drawio=…&view=original}); ohne Zusatz die Zeichnung, mit der Vorschau als Rückfall.
     */
    static final String DRAWIO_VIEW_ORIGINAL = "view=original";

    /** @return Zahl der gekennzeichneten draw.io-Bilder */
    static int drawioPreviews(Document doc, String baseUri) {
        int marked = 0;
        for (Element data : doc.select("[data-diagramdata]")) {
            JsonObject diagram;
            try {
                JsonElement parsed = JsonParser.parseString(new String(
                        Base64.getMimeDecoder().decode(data.attr("data-diagramdata")), StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) {
                    continue;
                }
                diagram = parsed.getAsJsonObject();
            } catch (RuntimeException e) {
                continue; // keine lesbaren Diagrammdaten: Platzhalter bleibt leer wie bisher
            }
            String preview = text(diagram, "previewName");
            String owner = text(diagram, "owningPageId");
            if (owner.isEmpty()) {
                owner = text(diagram, "ceoId");
            }
            if (preview.isEmpty() || !owner.matches("\\d+")) {
                continue;
            }
            Element macro = data.closest("[data-macro-name]");
            Element anchor = macro != null ? macro : data;
            String name = text(diagram, "diagramName");
            String revision = text(diagram, "revision");
            // Wie das Plugin selbst (drawio-reader.js): die Vorschau genau der eingebundenen Diagrammrevision.
            String query = revision.matches("\\d+") ? "?version=" + revision + "&api=v2" : "";
            if (query.isEmpty()) {
                // ohne Revision lädt Confluence die jüngste Vorschau; das kann eine andere Fassung sein als eingebunden
                LOG.info("[Confluence] draw.io-Diagramm ohne lesbare Revision (" + (revision.isEmpty() ? "fehlt"
                        : "nicht numerisch") + "): Vorschau ohne version, also die jüngste");
            }
            String src = baseUri + "download/attachments/" + owner + "/" + encodeSegment(preview) + query;
            if (!name.isEmpty()) {
                // Für den Bildlader: das Diagramm selbst (REST des Plugins), die Vorschau bleibt der Rückfall.
                // Der Fragmentteil geht nie über die Leitung.
                src += "#" + DRAWIO_FRAGMENT + "=" + encodeSegment(name) + "&page=" + owner
                        + (revision.matches("\\d+") ? "&revision=" + revision : "");
            }
            String alt = name.isEmpty() ? preview : name;
            // Liefert Confluence die Vorschau schon als <img> (export_view: /download/attachments/…png?version=…&
            // modificationDate=…), wird genau dieses Bild markiert; sonst ginge es am Diagramm vorbei als PNG über die
            // Leitung und landete bei SSO auf der Anmeldeseite (0.19.x, Angelos Lauf). Gilt für Bilder im Makro und
            // für jedes Bild der Seite, dessen Pfad diese Vorschau ist.
            Set<Element> present = new LinkedHashSet<Element>(anchor.select("img"));
            for (Element img : doc.select("img")) {
                if (isPreview(img.attr("src"), owner, preview)) {
                    present.add(img);
                }
            }
            marked++;
            if (!present.isEmpty()) {
                for (Element img : present) {
                    img.attr("src", src).attr("alt", alt).attr(DRAWIO_MARK, "1").removeAttr("srcset");
                }
                continue;
            }
            Element image = new Element("img")
                    .attr("src", src)
                    .attr("alt", alt)
                    .attr(DRAWIO_MARK, "1")
                    .attr("data-linked-resource-default-alias", preview);
            anchor.after(new Element("p").appendChild(image));
        }
        return marked;
    }

    /** Merkt im Dokument, welches Bild schon als draw.io-Zeichnung gekennzeichnet ist (wird nicht ausgegeben). */
    private static final String DRAWIO_MARK = "data-ea-drawio";

    /**
     * Zweiter Weg ohne {@code data-diagramdata}: Rendert {@code export_view} das Makro nur als Vorschaubild
     * ({@code /download/attachments/<Seite>/<Name>.png}), kommen Name, Revision und Eigentümerseite aus den
     * Makroparametern in {@code body.storage} ({@code diagramName}, {@code revision}, bei {@code inc-drawio}
     * {@code pageId}). Gekennzeichnet werden nur Bilder, deren Pfad genau diese Vorschau ist.
     *
     * @return Zahl der gekennzeichneten Bilder
     */
    static int storageDrawioPreviews(Document doc, String baseUri, String storage, String pageId) {
        if (storage == null || storage.isEmpty()) {
            return 0;
        }
        int marked = 0;
        for (String[] macro : storageMacros(storage)) {
            String name = macro[0];
            String owner = macro[1].isEmpty() ? (pageId == null ? "" : pageId) : macro[1];
            String revision = macro[2];
            if (name.isEmpty() || !owner.matches("\\d+")) {
                continue;
            }
            String preview = name + ".png";
            boolean numeric = revision.matches("\\d+");
            String src = baseUri + "download/attachments/" + owner + "/" + encodeSegment(preview)
                    + (numeric ? "?version=" + revision + "&api=v2" : "")
                    + "#" + DRAWIO_FRAGMENT + "=" + encodeSegment(name) + "&page=" + owner
                    + (numeric ? "&revision=" + revision : "");
            for (Element img : doc.select("img")) {
                if (!img.hasAttr(DRAWIO_MARK) && isPreview(img.attr("src"), owner, preview)) {
                    img.attr("src", src).attr("alt", name).attr(DRAWIO_MARK, "1").removeAttr("srcset");
                    marked++;
                }
            }
        }
        return marked;
    }

    /** draw.io-Makros im Seitenquelltext als {@code {diagramName, pageId oder "", revision oder ""}}. */
    static List<String[]> storageMacros(String storage) {
        List<String[]> macros = new ArrayList<String[]>();
        Document xml = Jsoup.parse(storage, "", org.jsoup.parser.Parser.xmlParser());
        for (Element macro : xml.getElementsByTag("ac:structured-macro")) {
            String kind = macro.attr("ac:name");
            if (!kind.equals("drawio") && !kind.equals("drawio-sketch") && !kind.equals("inc-drawio")) {
                continue;
            }
            String name = "";
            String page = "";
            String revision = "";
            for (Element parameter : macro.children()) {
                if (!parameter.tagName().equals("ac:parameter")) {
                    continue;
                }
                String key = parameter.attr("ac:name");
                String value = parameter.text().trim();
                if (key.equals("diagramName")) {
                    name = value;
                } else if (key.equals("pageId")) {
                    page = value;
                } else if (key.equals("revision")) {
                    revision = value;
                }
            }
            macros.add(new String[] {name, page, revision});
        }
        return macros;
    }

    /** Zeigt {@code src} (ohne Query, dekodiert) auf {@code …/download/attachments|thumbnails/<owner>/<preview>}? */
    static boolean isPreview(String src, String owner, String preview) {
        if (src == null || src.isEmpty()) {
            return false;
        }
        String path = src;
        for (char end : new char[] {'?', '#'}) {
            int at = path.indexOf(end);
            path = at < 0 ? path : path.substring(0, at);
        }
        try {
            path = java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8");
        } catch (java.io.UnsupportedEncodingException | IllegalArgumentException e) {
            return false;
        }
        return path.endsWith("/download/attachments/" + owner + "/" + preview)
                || path.endsWith("/download/thumbnails/" + owner + "/" + preview);
    }

    private static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }

    /** Ein Pfadsegment: alles außer A–Z, a–z, 0–9 und {@code -._~} prozentkodiert (UTF-8). */
    static String encodeSegment(String segment) {
        StringBuilder out = new StringBuilder();
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c < 0x80 && (Character.isLetterOrDigit(c) || "-._~".indexOf(c) >= 0)) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    /**
     * Links der Seite: unter der Server-Basis auf die eingestellte Basis umgeschrieben (wie Bilder), damit der
     * Lesebereich sie als eigene erkennt; ein Link, an dem Confluence die Content-ID der Zielseite nennt
     * ({@code data-linked-resource-type="page"}), zeigt danach auf {@code pages/viewpage.action?pageId=<id>} (Fragment
     * bleibt). So braucht ein Titel-Link ({@code /display/SPACE/Titel}) beim Klick keine Nachfrage.
     */
    private static void pageLinks(Document doc, String baseUri, String serverBase) {
        for (Element link : doc.select("a[href]")) {
            String raw = link.attr("href").trim();
            if (raw.isEmpty() || raw.startsWith("#")) {
                continue;
            }
            String href = link.absUrl("href");
            if (href.isEmpty()) {
                continue;
            }
            href = onBase(onBase(href, serverBase, baseUri, true), baseUri, baseUri, false);
            String rest = underBase(href, baseUri, false);
            String linkedId = link.attr("data-linked-resource-id").trim();
            if (rest != null && "page".equalsIgnoreCase(link.attr("data-linked-resource-type").trim())
                    && ConfluenceIds.isContentId(linkedId)) {
                int hash = rest.indexOf('#');
                String target = baseUri.endsWith("/") ? baseUri : baseUri + "/";
                href = target + ConfluenceLinks.VIEW_PAGE + linkedId + (hash < 0 ? "" : rest.substring(hash));
            }
            link.attr("href", href);
        }
    }

    /**
     * {@code src} unter {@code from} (Host ohne Groß-/Kleinschreibung, Port mit Standardport gleichgesetzt, Pfad
     * unter dem Kontextpfad von {@code from}) als Adresse unter {@code to}; sonst {@code src} unverändert. {@code to} bestimmt das Schema der Anfrage. {@code anyScheme}: auch ein anderes Schema
     * gilt als dieselbe Basis (nur für die von Confluence selbst gemeldete Server-Basis); sonst muss es gleich sein.
     */
    static String onBase(String src, String from, String to, boolean anyScheme) {
        String rest = underBase(src, from, anyScheme);
        if (rest == null) {
            return src;
        }
        String target = to.endsWith("/") ? to.substring(0, to.length() - 1) : to;
        return target + rest;
    }

    /**
     * Der Teil von {@code src} hinter dem Kontextpfad von {@code base} (beginnt mit {@code /}, roh mit Query und
     * Fragment), wenn {@code src} unter {@code base} liegt (Regeln wie {@link #onBase}); sonst {@code null}.
     */
    static String underBase(String src, String base, boolean anyScheme) {
        if (src == null || base == null || base.trim().isEmpty()) {
            return null;
        }
        URI address;
        URI root;
        try {
            address = new URI(src.trim());
            root = new URI(base.trim());
        } catch (URISyntaxException e) {
            return null;
        }
        if (!anyScheme && (address.getScheme() == null || !address.getScheme().equalsIgnoreCase(root.getScheme()))) {
            return null;
        }
        if (address.getHost() == null || root.getHost() == null || !address.getHost().equalsIgnoreCase(root.getHost())
                || port(address) != port(root) && !(isDefaultPort(address) && isDefaultPort(root))) {
            return null;
        }
        String context = root.getRawPath() == null ? "" : root.getRawPath();
        context = context.endsWith("/") ? context.substring(0, context.length() - 1) : context;
        String path = address.getRawPath() == null ? "" : address.getRawPath();
        if (!path.startsWith(context + "/")) {
            return null;
        }
        return path.substring(context.length())
                + (address.getRawQuery() == null ? "" : "?" + address.getRawQuery())
                + (address.getRawFragment() == null ? "" : "#" + address.getRawFragment());
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean isDefaultPort(URI uri) {
        return uri.getPort() < 0 || uri.getPort() == 443 || uri.getPort() == 80;
    }

    /**
     * Confluence nennt am {@code <img>} die nackte Content-ID, die REST-API die Form {@code att<id>}; {@code null}
     * ohne Anhang.
     */
    private static String attachmentId(String linked) {
        if (ConfluenceIds.isAttachmentId(linked)) {
            return linked;
        }
        return ConfluenceIds.isContentId(linked) ? "att" + linked : null;
    }

    /** Ein Bild der Seite mit den Angaben, die Confluence am {@code <img>} mitliefert. */
    static final class InlineImage {
        final String src;
        /** Anhang-ID ({@code att…}) oder {@code null}, wenn das Bild kein Anhang ist. */
        final String attachmentId;
        final String title;
        final String version;
        final String mediaType;
        final String containerId;

        InlineImage(String src, String attachmentId, String title, String version, String mediaType,
                    String containerId) {
            this.src = src;
            this.attachmentId = attachmentId;
            this.title = title;
            this.version = version;
            this.mediaType = mediaType;
            this.containerId = containerId;
        }
    }
}
