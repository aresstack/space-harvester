package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.jira.JiraRestClient.Attachment;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.jira.JiraRestClient.Comment;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.jira.JiraRestClient.Issue;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.jira.JiraRestClient.Link;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Die Lese-Ansicht eines Vorgangs (paketintern): Titel, Metadatentabelle, die von Jira gerenderten Felder
 * (Beschreibung, Umgebung) und Kommentare als HTML an ihrer Stelle, die Verknüpfungen als Links auf
 * {@code /browse/<KEY>} (die der Lesebereich über {@code resolveAddress} intern öffnet), zuletzt die Rohdaten:
 * die unveränderte JSON-Antwort der REST-API, eingerückt, in einem {@code <pre>}-Block. Bilder behalten ihre
 * {@code src}; der Lesebereich lädt sie über {@code loadViewImage} mit der Anmeldung der Quelle.
 */
final class JiraIssueView {

    /** Das HTML der Ansicht. */
    final String html;
    /** Die {@code src} aller Bilder in Lesereihenfolge, absolut gegen die Basis-URL. */
    final List<String> images;

    private JiraIssueView(String html, List<String> images) {
        this.html = html;
        this.images = images;
    }

    /**
     * @param base die Basis-URL mit abschließendem Schrägstrich; relative Links und Bilder werden dagegen aufgelöst
     */
    static JiraIssueView of(Issue issue, String base) {
        Set<String> images = new LinkedHashSet<String>();
        StringBuilder html = new StringBuilder();
        html.append("<html><body>");
        html.append("<h1>").append(escape(issue.title())).append("</h1>");
        html.append("<p><a href=\"").append(escape(base + "browse/" + issue.key)).append("\">")
                .append(escape(issue.key)).append(" in Jira öffnen</a></p>");
        html.append("<table border=\"0\" cellpadding=\"2\">");
        row(html, "Projekt", issue.projectName == null ? issue.projectKey
                : issue.projectKey == null ? issue.projectName : issue.projectName + " (" + issue.projectKey + ")");
        row(html, "Typ", issue.issueType);
        row(html, "Status", issue.status);
        row(html, "Priorität", issue.priority);
        row(html, "Lösung", issue.resolution);
        row(html, "Bearbeiter", issue.assignee);
        row(html, "Melder", issue.reporter);
        row(html, "Erstellt", JiraIssueText.format(issue.created));
        row(html, "Aktualisiert", JiraIssueText.format(issue.updated));
        row(html, "Gelöst", JiraIssueText.format(issue.resolved));
        row(html, "Labels", JiraIssueText.join(issue.labels));
        row(html, "Komponenten", JiraIssueText.join(issue.components));
        row(html, "Lösungsversionen", JiraIssueText.join(issue.fixVersions));
        if (issue.parentKey != null) {
            html.append("<tr><td><b>Übergeordnet</b></td><td>").append(issueLink(base, issue.parentKey, ""))
                    .append("</td></tr>");
        }
        html.append("</table>");
        section(html, "Beschreibung", issue.renderedDescription, issue.description, base, images);
        section(html, "Umgebung", issue.renderedEnvironment, issue.environment, base, images);
        if (!issue.links.isEmpty()) {
            html.append("<h2>Verknüpfungen</h2><ul>");
            for (Link link : issue.links) {
                html.append("<li>").append(link.relation.isEmpty() ? "" : escape(link.relation) + " ")
                        .append(issueLink(base, link.key, link.summary)).append("</li>");
            }
            html.append("</ul>");
        }
        if (!issue.attachments.isEmpty()) {
            html.append("<h2>Anhänge (").append(issue.attachments.size()).append(")</h2><ul>");
            for (Attachment attachment : issue.attachments) {
                html.append("<li>").append(escape(attachment.fileName == null ? attachment.id : attachment.fileName));
                if (attachment.size >= 0) {
                    html.append(" (").append(size(attachment.size)).append(")");
                }
                if (attachment.author != null) {
                    html.append(", ").append(escape(attachment.author));
                }
                html.append("</li>");
            }
            html.append("</ul>");
        }
        html.append("<h2>Kommentare (").append(issue.commentTotal > issue.comments.size() ? issue.commentTotal
                : issue.comments.size()).append(")</h2>");
        if (issue.comments.isEmpty()) {
            html.append("<p>Keine Kommentare.</p>");
        }
        for (Comment comment : issue.comments) {
            html.append("<h3>").append(escape(comment.author == null ? "Unbekannt" : comment.author));
            if (comment.created != null) {
                html.append(", ").append(escape(JiraIssueText.format(comment.created)));
            }
            html.append("</h3>");
            html.append(rendered(comment.html, comment.body, base, images));
        }
        if (issue.commentTotal > issue.comments.size()) {
            html.append("<p><i>").append(issue.commentTotal - issue.comments.size())
                    .append(" weitere Kommentare in Jira.</i></p>");
        }
        html.append("<h2>Rohdaten</h2>");
        html.append("<p>Antwort der REST-API (<code>/rest/api/2/issue/").append(escape(issue.key))
                .append("</code>), unverändert:</p>");
        html.append("<pre>").append(escape(pretty(issue.raw))).append("</pre>");
        html.append("</body></html>");
        return new JiraIssueView(html.toString(), new ArrayList<String>(images));
    }

    private static void section(StringBuilder html, String heading, String renderedHtml, String markup, String base,
                                Set<String> images) {
        boolean hasHtml = renderedHtml != null && !renderedHtml.trim().isEmpty();
        boolean hasMarkup = markup != null && !markup.trim().isEmpty();
        if (!hasHtml && !hasMarkup) {
            return;
        }
        html.append("<h2>").append(escape(heading)).append("</h2>");
        html.append(rendered(renderedHtml, markup, base, images));
    }

    /** Gerendertes HTML bereinigt und mit absoluten Adressen; ohne HTML das Wiki-Markup als Vorformatiertes. */
    private static String rendered(String renderedHtml, String markup, String base, Set<String> images) {
        if (renderedHtml == null || renderedHtml.trim().isEmpty()) {
            return markup == null || markup.trim().isEmpty() ? ""
                    : "<pre>" + escape(markup.trim()) + "</pre>";
        }
        Document doc = Jsoup.parseBodyFragment(renderedHtml, base);
        doc.select("script, style, noscript, iframe, object, embed, form, input, button").remove();
        doc.select("[style~=(?i)display:\\s*none]").remove();
        for (Element img : doc.select("img[src]")) {
            String absolute = img.absUrl("src");
            if (absolute.isEmpty()) {
                img.remove();
                continue;
            }
            img.attr("src", absolute);
            images.add(absolute);
        }
        for (Element a : doc.select("a[href]")) {
            String absolute = a.absUrl("href");
            if (!absolute.isEmpty()) {
                a.attr("href", absolute);
            }
        }
        return doc.body().html();
    }

    private static String issueLink(String base, String key, String summary) {
        return "<a href=\"" + escape(base + "browse/" + key) + "\">" + escape(key) + "</a>"
                + (summary == null || summary.isEmpty() ? "" : ": " + escape(summary));
    }

    private static void row(StringBuilder html, String label, String value) {
        if (value != null && !value.trim().isEmpty()) {
            html.append("<tr><td><b>").append(escape(label)).append("</b></td><td>").append(escape(value.trim()))
                    .append("</td></tr>");
        }
    }

    /** Die Rohdaten eingerückt; ist die Antwort kein lesbares JSON, unverändert. */
    static String pretty(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        try {
            JsonElement element = JsonParser.parseString(raw);
            return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
                    .toJson(element);
        } catch (JsonParseException e) {
            return raw;
        }
    }

    private static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '&':
                    sb.append("&amp;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
