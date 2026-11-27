package com.aresstack.spaceharvester.source;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest die Seiten der BSCW-Weboberfläche, die die Schnellsuche braucht: das Formular-Token eines Ordners und die
 * Treffer eines Suchordners. Die Weboberfläche hat keine maschinenlesbare Suche; ihre Trefferliste ist eine Tabelle mit
 * einer Zeile je Objekt ({@code id="row<Suchordner>_<Objekt>"}), dem Namen in {@code <span id="name…">} und dem
 * Ordnerpfad darunter ({@code class="ind_sub"}). Die Objektnummer ist zugleich die WebDAV-Adresse
 * {@code /sec/bscw.cgi/<Objekt>}, unter der die Quelle das Dokument lädt.
 */
final class BscwSearchPage {

    /** Formularwert {@code qsmode}: Name, Beschreibung, Inhalt und Tags. */
    static final String MODE_ALL = "q_search_all";
    /** Formularwert {@code qsmode}: nur der Dokumentinhalt. */
    static final String MODE_CONTENT = "q_search_content";

    private static final Pattern TOKEN = Pattern.compile(
            "name=\"bscw_v_post\"\\s+value=\"([^\"]{1,256})\"");
    private static final Pattern ROW = Pattern.compile(
            "<tr class=\"highlight[^\"]*\" id=\"row(\\d{1,20})_(\\d{1,20})\">");
    private static final Pattern NAME = Pattern.compile(
            "<span id=\"name\\d{1,20}_(\\d{1,20})\">(.*?)</span>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<a\\b[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern PATH = Pattern.compile("<table summary=\"\" class=\"ind_sub\">(.*?)</table>",
            Pattern.DOTALL);
    private static final Pattern DATE = Pattern.compile(
            "<td class=\"box cdate[^\"]*\"><div[^>]*>([^<]{1,40})</div>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private BscwSearchPage() {
    }

    /** Ein Treffer der Schnellsuche. */
    record Hit(String objectId, String name, boolean document, List<String> folders, String modified) {
    }

    /** Das Formular-Token ({@code bscw_v_post}) einer Seite; {@code null}, wenn die Seite keines trägt. */
    static String token(String html) {
        Matcher matcher = TOKEN.matcher(html);
        return matcher.find() ? unescape(matcher.group(1)) : null;
    }

    /** Die Treffer eines Suchordners in der Reihenfolge der Seite, jedes Objekt einmal, höchstens {@code limit}. */
    static List<Hit> hits(String html, int limit) {
        List<Hit> hits = new ArrayList<>();
        Matcher row = ROW.matcher(html);
        List<Integer> starts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        while (row.find()) {
            starts.add(row.start());
            ids.add(row.group(2));
        }
        for (int i = 0; i < ids.size() && hits.size() < limit; i++) {
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : html.length();
            String block = html.substring(from, to);
            String id = ids.get(i);
            if (hits.stream().anyMatch(hit -> hit.objectId().equals(id))) {
                continue;
            }
            Matcher name = NAME.matcher(block);
            if (!name.find() || !name.group(1).equals(id)) {
                continue;
            }
            Matcher link = LINK.matcher(name.group(2));
            String title;
            boolean document = false;
            if (link.find()) {
                title = text(link.group(2));
                document = link.group(1).startsWith("d" + id + "/");
            } else {
                title = text(name.group(2));
            }
            if (title.isEmpty()) {
                continue;
            }
            List<String> folders = new ArrayList<>();
            Matcher path = PATH.matcher(block);
            if (path.find()) {
                Matcher part = LINK.matcher(path.group(1));
                while (part.find()) {
                    String href = part.group(1);
                    // Das letzte Glied ist der Treffer selbst; nur die Ordner davor bilden den Ort.
                    if (!href.startsWith("d" + id + "/") && !href.equals(id)) {
                        folders.add(text(part.group(2)));
                    }
                }
            }
            Matcher date = DATE.matcher(block);
            hits.add(new Hit(id, title, document, List.copyOf(folders), date.find() ? text(date.group(1)) : null));
        }
        return List.copyOf(hits);
    }

    private static String text(String html) {
        return unescape(TAG.matcher(html).replaceAll("")).replaceAll("\\s+", " ").strip();
    }

    static String unescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int end = c == '&' ? text.indexOf(';', i) : -1;
            if (end < 0 || end - i > 10) {
                out.append(c);
                continue;
            }
            String entity = text.substring(i + 1, end);
            String plain = switch (entity) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos", "#39" -> "'";
                case "nbsp" -> " ";
                default -> numeric(entity);
            };
            if (plain == null) {
                out.append(c);
                continue;
            }
            out.append(plain);
            i = end;
        }
        return out.toString();
    }

    private static String numeric(String entity) {
        try {
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                return new String(Character.toChars(Integer.parseInt(entity.substring(2), 16)));
            }
            if (entity.startsWith("#")) {
                return new String(Character.toChars(Integer.parseInt(entity.substring(1))));
            }
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        return null;
    }
}
