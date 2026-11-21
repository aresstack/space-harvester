package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Was die Jira-Werkzeuge des Chats an einer Jira-Quelle tun (Schritt 3 der Jira-Anbindung): JQL-Suche und Übergänge
 * lesen, Kommentar schreiben, Übergang ausführen. Die Wissensquelle selbst bleibt nur lesend; die schreibenden
 * Methoden ruft nur ein Werkzeug auf, das vor jedem Aufruf die ausdrückliche Freigabe des Benutzers einholt
 * ({@code ToolApprovalGate}, Vorgabe {@code ASK}). Anmeldung, Proxy und mTLS sind die der Quelle.
 */
public interface JiraIssueOperations {

    /** Die Basis-URL dieser Jira (für Links und die Freigabefrage). */
    URI baseUrl();

    /**
     * Der Vorgangs-Schlüssel zu einer Adresse dieser Jira ({@code …/browse/KEY-1}, {@code jira://…/issue/KEY-1}) oder
     * zu einem nackten Schlüssel ({@code KEY-1}); {@code null}, wenn es keiner dieser Jira ist.
     */
    String issueKey(String keyOrAddress);

    /**
     * JQL-Suche mit der Einschränkung der Quelle ({@code jql} in den Einstellungen), höchstens {@code limit}
     * Vorgänge (1 bis 100).
     */
    IssueSearch searchIssues(String jql, int limit) throws KnowledgeSourceException;

    /** Kopfdaten eines Vorgangs; {@code NOT_FOUND}, wenn es ihn nicht gibt oder das Konto ihn nicht sieht. */
    IssueSummary issueSummary(String issueKey) throws KnowledgeSourceException;

    /** Die Übergänge, die das Konto am Vorgang gerade ausführen darf. */
    List<Transition> transitions(String issueKey) throws KnowledgeSourceException;

    /** Schreibt einen Kommentar (Wiki-Markup von Jira); gibt die Kommentar-ID zurück. Nur nach Freigabe. */
    String addComment(String issueKey, String body) throws KnowledgeSourceException;

    /** Führt den Übergang {@code transitionId} aus. Nur nach Freigabe. */
    void transition(String issueKey, String transitionId) throws KnowledgeSourceException;

    /** Kopfdaten eines Vorgangs. */
    final class IssueSummary {
        private final String key;
        private final String summary;
        private final String issueType;
        private final String status;
        private final String assignee;
        private final Instant updated;
        private final URI url;

        public IssueSummary(String key, String summary, String issueType, String status, String assignee,
                            Instant updated, URI url) {
            this.key = key;
            this.summary = summary;
            this.issueType = issueType;
            this.status = status;
            this.assignee = assignee;
            this.updated = updated;
            this.url = url;
        }

        public String key() {
            return key;
        }

        public String summary() {
            return summary;
        }

        public String issueType() {
            return issueType;
        }

        public String status() {
            return status;
        }

        public String assignee() {
            return assignee;
        }

        /** Letzte Änderung; Version der Freigabe (ändert sich der Vorgang dazwischen, gilt sie nicht mehr). */
        public Instant updated() {
            return updated;
        }

        public URI url() {
            return url;
        }
    }

    /** Ergebnis einer JQL-Suche: die gezeigten Vorgänge und die Gesamtzahl laut Jira ({@code -1} unbekannt). */
    final class IssueSearch {
        private final List<IssueSummary> issues;
        private final int total;

        public IssueSearch(List<IssueSummary> issues, int total) {
            this.issues = Collections.unmodifiableList(issues);
            this.total = total;
        }

        public List<IssueSummary> issues() {
            return issues;
        }

        public int total() {
            return total;
        }
    }

    /** Ein ausführbarer Übergang: ID, Name und Zielstatus. */
    final class Transition {
        private final String id;
        private final String name;
        private final String toStatus;

        public Transition(String id, String name, String toStatus) {
            this.id = id;
            this.name = name;
            this.toStatus = toStatus;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public String toStatus() {
            return toStatus;
        }
    }
}
