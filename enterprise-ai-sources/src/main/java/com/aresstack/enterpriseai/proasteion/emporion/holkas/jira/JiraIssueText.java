package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.jira.JiraRestClient.Comment;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.jira.JiraRestClient.Issue;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.jira.JiraRestClient.Link;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Die Textfassung eines Vorgangs für Index und Chat (paketintern): Titel als {@code #}-Zeile, die Metadaten als
 * „Feld: Wert“-Zeilen, dann Beschreibung, Umgebung, Verknüpfungen und Kommentare als Abschnitte. Gerenderte Felder
 * ({@code renderedFields}) werden über {@link JiraHtmlText} in Text überführt; fehlen sie, steht das Wiki-Markup.
 */
final class JiraIssueText {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private JiraIssueText() {
    }

    static String of(Issue issue) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(issue.title()).append("\n\n");
        line(out, "Projekt", issue.projectName == null ? issue.projectKey
                : issue.projectKey == null ? issue.projectName : issue.projectName + " (" + issue.projectKey + ")");
        line(out, "Typ", issue.issueType);
        line(out, "Status", issue.status);
        line(out, "Priorität", issue.priority);
        line(out, "Lösung", issue.resolution);
        line(out, "Bearbeiter", issue.assignee);
        line(out, "Melder", issue.reporter);
        line(out, "Erstellt", format(issue.created));
        line(out, "Aktualisiert", format(issue.updated));
        line(out, "Gelöst", format(issue.resolved));
        line(out, "Labels", join(issue.labels));
        line(out, "Komponenten", join(issue.components));
        line(out, "Lösungsversionen", join(issue.fixVersions));
        line(out, "Übergeordnet", issue.parentKey);
        section(out, "Beschreibung", issue.renderedDescription, issue.description);
        section(out, "Umgebung", issue.renderedEnvironment, issue.environment);
        if (!issue.links.isEmpty()) {
            out.append("\n## Verknüpfungen\n\n");
            for (Link link : issue.links) {
                out.append("- ").append(link.relation.isEmpty() ? "" : link.relation + " ").append(link.key);
                if (!link.summary.isEmpty()) {
                    out.append(": ").append(link.summary);
                }
                out.append('\n');
            }
        }
        if (!issue.comments.isEmpty()) {
            out.append("\n## Kommentare\n");
            for (Comment comment : issue.comments) {
                out.append("\n### ").append(comment.author == null ? "Unbekannt" : comment.author);
                if (comment.created != null) {
                    out.append(", ").append(format(comment.created));
                }
                out.append("\n\n");
                String text = comment.html != null ? JiraHtmlText.toText(comment.html)
                        : comment.body == null ? "" : comment.body.trim();
                if (!text.isEmpty()) {
                    out.append(text).append('\n');
                }
            }
        }
        return out.toString().trim();
    }

    private static void section(StringBuilder out, String heading, String html, String markup) {
        String text = html != null && !html.trim().isEmpty() ? JiraHtmlText.toText(html)
                : markup == null ? "" : markup.trim();
        if (text.isEmpty()) {
            return;
        }
        out.append("\n## ").append(heading).append("\n\n").append(text).append('\n');
    }

    private static void line(StringBuilder out, String label, String value) {
        if (value != null && !value.trim().isEmpty()) {
            out.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    static String format(Instant instant) {
        return instant == null ? null : DATE.format(instant);
    }

    static String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(", ", values);
    }
}
