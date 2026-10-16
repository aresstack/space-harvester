package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Was eine HTML-Antwort ist, die statt einer Datei (Bild, Anhang, Vorschau) kam (paketintern).
 *
 * <p>Bisher stand im Log nur „vermutlich eine Anmeldeseite“, geraten aus dem Content-Type. Diese Klasse liest die
 * Seite: Titel, erste Überschrift, Textanfang des Inhaltsbereichs, Formulare (Ziel ohne Query, Methode, Feldnamen und
 * -typen, Schaltflächen), Meta-Refresh, Hosts der Formular-, Refresh- und Weiterleitungsziele und den angemeldeten
 * Benutzer, den eine Confluence-Seite in {@code <meta name="ajs-remote-user">} nennt. Daraus folgt eine grobe
 * {@link Kind}. Werte von Eingabefeldern, Cookies und Header kommen nie in die Zeile.
 *
 * <p>Gesucht wird nur in Titel, Überschriften, Formularen und Inhaltsbereich, nicht in Kopf- und Fußleiste: Eine
 * angemeldete Confluence-Seite verlinkt im Benutzermenü auch die Zwei-Faktor-Einstellungen, das ist keine Abfrage.
 */
final class HtmlResponseDiagnosis {

    /** Grobe Art der Seite; die Reihenfolge ist die Prüfreihenfolge. */
    enum Kind {
        /** Zwei-Faktor-/Einmalcode-Abfrage (OTP, 2FA, Bestätigungscode). */
        TWO_FACTOR,
        /** Anmeldeformular von Confluence (Seraph: {@code os_username}, {@code dologin.action}). */
        CONFLUENCE_LOGIN,
        /** Weiterleitung oder Formular einer SSO-Anmeldung (SAML, OpenID Connect, OAuth, Kerberos). */
        SSO,
        /** Fehler- oder Verweigerungsseite. */
        ERROR_PAGE,
        /** Sonstige Seite mit Formular. */
        OTHER_FORM,
        /** Gewöhnliche Confluence-Seite ohne Formular. */
        CONFLUENCE_PAGE,
        /** Nicht zuzuordnen (etwa die Seite eines vorgeschalteten Proxys). */
        OTHER
    }

    private static final int TEXT_LIMIT = 300;

    private final Kind kind;
    private final String title;
    private final String heading;
    private final String text;
    private final List<String> forms;
    private final String metaRefresh;
    private final Set<String> hosts;
    private final Set<String> markers;
    private final String remoteUser;
    private final boolean confluencePage;

    private HtmlResponseDiagnosis(Kind kind, String title, String heading, String text, List<String> forms,
                                  String metaRefresh, Set<String> hosts, Set<String> markers, String remoteUser,
                                  boolean confluencePage) {
        this.kind = kind;
        this.title = title;
        this.heading = heading;
        this.text = text;
        this.forms = forms;
        this.metaRefresh = metaRefresh;
        this.hosts = hosts;
        this.markers = markers;
        this.remoteUser = remoteUser;
        this.confluencePage = confluencePage;
    }

    /**
     * @param uri       die zuletzt angefragte Adresse (Basis für relative Ziele)
     * @param body      der Antwortkörper
     * @param redirects Ziele der Weiterleitungen auf dem Weg dorthin (für die Hostliste), darf leer sein
     */
    static HtmlResponseDiagnosis of(URI uri, byte[] body, List<String> redirects) {
        Document document = Jsoup.parse(new String(body, StandardCharsets.UTF_8), uri.toString());
        String title = clean(document.title());
        Element headingElement = document.selectFirst("#main h1, #content h1, h1, h2, h3");
        String heading = headingElement == null ? "" : clean(headingElement.text());
        Element content = document.selectFirst("#main-content, #main, #content, main, [role=main]");
        if (content == null) {
            content = document.body() == null ? document : document.body().clone();
            content.select("header, footer, nav, #header, #footer, script, style, noscript").remove();
        }
        String text = cut(clean(content.text()));

        Set<String> hosts = new LinkedHashSet<String>();
        for (String redirect : redirects) {
            addHost(hosts, uri, redirect);
        }
        List<String> forms = new ArrayList<String>();
        StringBuilder searchable = new StringBuilder(title).append(' ').append(heading).append(' ')
                .append(content.text()).append(' ');
        boolean confluenceLogin = false;
        for (Element form : document.select("form")) {
            if (form.closest("header, #header, nav, footer, #footer") != null) {
                continue;
            }
            String action = form.hasAttr("action") ? form.absUrl("action") : "";
            if (action.isEmpty()) {
                action = form.attr("action");
            }
            addHost(hosts, uri, action);
            StringBuilder line = new StringBuilder();
            line.append(form.attr("method").isEmpty() ? "GET" : form.attr("method").toUpperCase(Locale.ROOT))
                    .append(' ')
                    .append(action.isEmpty() ? "(gleiche Adresse)" : ConfluenceRestClient.withoutQuery(action));
            if (!form.id().isEmpty()) {
                line.append(" id=").append(form.id());
            }
            List<String> fields = new ArrayList<String>();
            for (Element input : form.select("input, select, textarea")) {
                String type = input.tagName().equals("input")
                        ? (input.attr("type").isEmpty() ? "text" : input.attr("type").toLowerCase(Locale.ROOT))
                        : input.tagName();
                String name = input.attr("name").isEmpty() ? "#" + input.id() : input.attr("name");
                fields.add(name + ":" + type);
                searchable.append(name).append(' ').append(input.id()).append(' ')
                        .append(input.attr("placeholder")).append(' ').append(input.attr("autocomplete")).append(' ');
                confluenceLogin |= "os_username".equals(input.attr("name"));
            }
            List<String> buttons = new ArrayList<String>();
            for (Element button : form.select("button, input[type=submit], input[type=button]")) {
                String label = button.tagName().equals("button") ? clean(button.text()) : clean(button.attr("value"));
                if (!label.isEmpty() && !button.attr("type").equalsIgnoreCase("hidden")) {
                    buttons.add(label);
                }
            }
            for (Element label : form.select("label")) {
                searchable.append(label.text()).append(' ');
            }
            line.append(" felder=").append(fields);
            if (!buttons.isEmpty()) {
                line.append(" knöpfe=").append(buttons);
            }
            forms.add(line.toString());
            searchable.append(action).append(' ').append(form.id()).append(' ');
            confluenceLogin |= action.contains("dologin.action") || "loginform".equals(form.id());
        }
        String refresh = "";
        Element meta = document.selectFirst("meta[http-equiv~=(?i)refresh]");
        if (meta != null) {
            String value = meta.attr("content");
            int url = value.toLowerCase(Locale.ROOT).indexOf("url=");
            refresh = url < 0 ? value.trim() : value.substring(url + 4).trim().replace("'", "").replace("\"", "");
            addHost(hosts, uri, refresh);
            searchable.append(refresh).append(' ');
            refresh = ConfluenceRestClient.withoutQuery(refresh);
        }
        Element user = document.selectFirst("meta[name=ajs-remote-user]");
        String remoteUser = user == null ? null : user.attr("content");
        boolean confluencePage = document.selectFirst("meta[name^=ajs-]") != null;

        String haystack = searchable.toString().toLowerCase(Locale.ROOT);
        Set<String> markers = new LinkedHashSet<String>();
        mark(markers, haystack, "2fa", "twofactor", "two-factor", "two factor", "zwei-faktor", "otp", "one-time",
                "einmalcode", "einmalpasswort", "einmal-passwort", "totp", "mfa", "verification code",
                "bestätigungscode", "sicherheitscode", "passcode");
        boolean twoFactor = !markers.isEmpty();
        Set<String> sso = new LinkedHashSet<String>();
        mark(sso, haystack, "saml", "openid", "oidc", "oauth", "kerberos", "spnego", "/adfs/", "sso");
        Set<String> error = new LinkedHashSet<String>();
        mark(error, haystack, "access denied", "zugriff verweigert", "not permitted", "keine berechtigung",
                "forbidden", "page not found", "nicht gefunden", "error", "fehler");
        markers.addAll(sso);
        markers.addAll(error);
        if (confluenceLogin) {
            markers.add("os_username/dologin");
        }
        Kind kind = twoFactor ? Kind.TWO_FACTOR
                : confluenceLogin ? Kind.CONFLUENCE_LOGIN
                : !sso.isEmpty() ? Kind.SSO
                : !error.isEmpty() && forms.isEmpty() ? Kind.ERROR_PAGE
                : !forms.isEmpty() ? Kind.OTHER_FORM
                : confluencePage ? Kind.CONFLUENCE_PAGE
                : Kind.OTHER;
        return new HtmlResponseDiagnosis(kind, title, heading, text, Collections.unmodifiableList(forms), refresh,
                Collections.unmodifiableSet(hosts), Collections.unmodifiableSet(markers), remoteUser, confluencePage);
    }

    Kind kind() {
        return kind;
    }

    String title() {
        return title;
    }

    String heading() {
        return heading;
    }

    String text() {
        return text;
    }

    List<String> forms() {
        return forms;
    }

    String metaRefresh() {
        return metaRefresh;
    }

    Set<String> hosts() {
        return hosts;
    }

    Set<String> markers() {
        return markers;
    }

    /** Benutzer laut {@code ajs-remote-user}; leer = anonym; {@code null}, wenn die Seite das Feld nicht hat. */
    String remoteUser() {
        return remoteUser;
    }

    boolean confluencePage() {
        return confluencePage;
    }

    /** Eine Logzeile ohne Werte von Eingabefeldern, Cookies und Header. */
    String summary() {
        return "art=" + kind
                + " titel=\"" + title + "\""
                + " überschrift=\"" + heading + "\""
                + " confluenceSeite=" + confluencePage
                + " angemeldetLautSeite=" + (remoteUser == null ? "-" : remoteUser.isEmpty() ? "(anonym)" : remoteUser)
                + " formulare=" + forms
                + " metaRefresh=" + (metaRefresh.isEmpty() ? "-" : metaRefresh)
                + " zielHosts=" + hosts
                + " merkmale=" + markers
                + " text=\"" + text + "\"";
    }

    /**
     * Die Seite für die Diagnosedatei: unverändert bis auf die Werte von Eingabefeldern und Meta-Feldern, deren Name
     * „token“ enthält (Formular- und CSRF-Werte der Sitzung); Namen und Typen bleiben.
     */
    static String redacted(URI uri, byte[] body) {
        Document document = Jsoup.parse(new String(body, StandardCharsets.UTF_8), uri.toString());
        document.outputSettings().prettyPrint(false);
        for (Element input : document.select("input[value]")) {
            input.attr("value", "***");
        }
        for (Element meta : document.select("meta[content]")) {
            String name = (meta.attr("name") + meta.attr("id")).toLowerCase(Locale.ROOT);
            if (name.contains("token")) {
                meta.attr("content", "***");
            }
        }
        return document.outerHtml();
    }

    private static void mark(Set<String> markers, String haystack, String... words) {
        for (String word : words) {
            if (word.length() <= 4 ? containsWord(haystack, word) : haystack.contains(word)) {
                markers.add(word);
            }
        }
    }

    /** Kurze Merkmale („otp“, „sso“) nur als eigenes Wort, nicht als Teil eines längeren. */
    private static boolean containsWord(String haystack, String word) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(word, from);
            if (at < 0) {
                return false;
            }
            int end = at + word.length();
            boolean startOk = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            boolean endOk = end >= haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static void addHost(Set<String> hosts, URI base, String target) {
        if (target == null || target.trim().isEmpty()) {
            return;
        }
        try {
            URI resolved = base.resolve(target.trim().replace(" ", "%20"));
            if (resolved.getHost() != null) {
                hosts.add(resolved.getHost().toLowerCase(Locale.ROOT));
            }
        } catch (IllegalArgumentException e) {
            // kein auswertbares Ziel
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('"', '\'').replaceAll("\\s+", " ").trim();
    }

    private static String cut(String value) {
        return value.length() <= TEXT_LIMIT ? value : value.substring(0, TEXT_LIMIT) + " …";
    }
}
