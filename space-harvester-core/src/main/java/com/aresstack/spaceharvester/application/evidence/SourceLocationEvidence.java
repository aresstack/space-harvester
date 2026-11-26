package com.aresstack.spaceharvester.application.evidence;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Quellstellenbeleg (ADR 0032, Fortschreibung vom 26.11.2026; ADR 0041, Punkt 7): belegt eine fachliche Aussage über
 * Programmcode mit einer oder mehreren zusammengehörigen Codestellen. Eine Regel kann sich über mehrere Paragraphen,
 * Programme oder Copybooks erstrecken; deshalb trägt jede Codestelle ihr eigenes Dokument und ihre eigene Revision.
 *
 * <p>Der Wortabgleich aus ADR 0032 gilt hier nicht: Eine Bedingung wie {@code IF KZ-STATUS = 'A'} enthält die
 * Inhaltswörter der fachlichen Aussage nicht. Geprüft wird technisch ({@link SourceLocationCheck}: Stellen vorhanden,
 * Revisionen unverändert) und fachlich durch einen Menschen. Ein Quellstellenbeleg belegt immer nur den Ist-Zustand.
 *
 * <p>Die Revisionen und die Analyseversion stammen aus der Identität der gespeicherten Programmanalyse (Prozessor,
 * Version, Inhalts-Hash; ADR 0041, Punkte 3 und 10), nie aus einer zweiten Revisionsquelle.
 *
 * @param program         das Programm, dessen Analyse die Aussage hervorgebracht hat
 * @param revision        Revision des Programms (Inhalts-Hash)
 * @param sites           belegende Codestellen, mindestens eine; Stellen im Programm tragen dessen Revision
 * @param facts           Parserfakten, auf die sich die Aussage stützt; jeder liegt in einer der Codestellen
 * @param dependencies    maßgebliche Abhängigkeiten (aufgelöste Copybooks samt Revision und negative Proben)
 * @param analysisVersion Prozessor und Version der Analyse, aus der die Parserfakten stammen
 */
public record SourceLocationEvidence(DocumentKey program, String revision, List<CodeSite> sites,
                                     List<ParserFact> facts, List<Dependency> dependencies, String analysisVersion)
        implements Evidence {

    /** Trennt die Felder im Fingerabdruck; kommt in Kennungen, Revisionen und Namen nicht vor. */
    private static final char SEPARATOR = '\u0000';
    private static final String DIGEST = "SHA-256";

    public SourceLocationEvidence {
        Objects.requireNonNull(program, "program");
        revision = requireText(revision, "revision");
        analysisVersion = requireText(analysisVersion, "analysisVersion");
        sites = List.copyOf(Objects.requireNonNull(sites, "sites"));
        facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        if (sites.isEmpty()) {
            throw new IllegalArgumentException("Ein Quellstellenbeleg braucht mindestens eine Codestelle");
        }
        for (CodeSite site : sites) {
            if (site.document().equals(program) && !site.revision().equals(revision)) {
                throw new IllegalArgumentException("Codestelle im Programm mit fremder Revision " + site.revision());
            }
        }
        for (ParserFact fact : facts) {
            if (fact.site() >= sites.size()) {
                throw new IllegalArgumentException("Parserfakt verweist auf Codestelle " + fact.site()
                        + ", es gibt nur " + sites.size());
            }
            CodeSite site = sites.get(fact.site());
            if (fact.firstLine() < site.firstLine() || fact.lastLine() > site.lastLine()) {
                throw new IllegalArgumentException("Parserfakt in Zeilen " + fact.firstLine() + ".." + fact.lastLine()
                        + " liegt außerhalb seiner Codestelle " + site.firstLine() + ".." + site.lastLine());
            }
        }
    }

    @Override
    public DocumentKey document() {
        return program;
    }

    @Override
    public Kind kind() {
        return Kind.SOURCE_LOCATION;
    }

    @Override
    public Optional<Bearing> bearing() {
        return Optional.of(Bearing.IST);
    }

    /**
     * Fingerabdruck des belegten Stands: Programm und Revision, jede Codestelle mit Dokument, Revision und Zeilen, jede
     * Abhängigkeit mit Revision (auch negative Proben) und die Analyseversion. Eine Bestätigung durch einen Menschen
     * gilt genau für diesen Fingerabdruck. Ändert sich eins davon, ändert er sich, und die frühere Bestätigung wandert
     * nicht mit (wie die Fassung in der ID einer Aussage aus Text). Parserfakten gehen nicht ein: Sie folgen
     * deterministisch aus Code und Analyseversion.
     */
    public String fingerprint() {
        StringBuilder value = new StringBuilder().append(program.value()).append(SEPARATOR).append(revision)
                .append(SEPARATOR).append(analysisVersion);
        for (CodeSite site : sites) {
            value.append(SEPARATOR).append(site.document().value()).append(SEPARATOR).append(site.revision())
                    .append(SEPARATOR).append(site.firstLine()).append(SEPARATOR).append(site.lastLine());
        }
        for (Dependency dependency : dependencies) {
            value.append(SEPARATOR).append(dependency.name()).append(SEPARATOR)
                    .append(dependency.document().map(DocumentKey::value).orElse("")).append(SEPARATOR)
                    .append(dependency.revision().orElse(""));
        }
        try {
            byte[] digest = MessageDigest.getInstance(DIGEST).digest(value.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " darf nicht leer sein");
        }
        return value;
    }

    /**
     * Eine zusammenhängende Codestelle.
     *
     * @param document  Programm, Copybook oder weiteres Programm
     * @param revision  Revision dieses Dokuments (Inhalts-Hash)
     * @param firstLine erste Zeile ab 1
     * @param lastLine  letzte Zeile (einschließlich)
     */
    public record CodeSite(DocumentKey document, String revision, int firstLine, int lastLine) {

        public CodeSite {
            Objects.requireNonNull(document, "document");
            revision = requireText(revision, "revision");
            if (firstLine < 1 || lastLine < firstLine) {
                throw new IllegalArgumentException("Zeilen " + firstLine + ".." + lastLine + " ungültig");
            }
        }

        /**
         * Die Zeichen dieser Zeilen im Text desselben Dokuments, damit „im Text zeigen“ die Codestelle markiert; leer,
         * wenn der Text weniger Zeilen hat.
         *
         * @return Beginn der ersten Zeile und Ende der letzten (exklusiv, ohne Zeilenumbruch)
         */
        public Optional<Characters> characters(String text) {
            int start = 0;
            for (int line = 1; line < firstLine; line++) {
                int next = text.indexOf('\n', start);
                if (next < 0) {
                    return Optional.empty();
                }
                start = next + 1;
            }
            int end = start;
            for (int line = firstLine; line <= lastLine; line++) {
                int next = text.indexOf('\n', end);
                if (next < 0) {
                    if (line < lastLine || start >= text.length()) {
                        return Optional.empty();
                    }
                    end = text.length();
                    break;
                }
                end = line == lastLine ? next : next + 1;
            }
            if (end > start && text.charAt(end - 1) == '\r') {
                end--;
            }
            return Optional.of(new Characters(start, end));
        }
    }

    /** Ein Zeichenbereich im Text, Ende exklusiv. */
    public record Characters(int start, int end) {
    }

    /**
     * Ein Parserfakt, auf den sich die Aussage stützt (ADR 0041, Punkt 7: deterministischer technischer Fakt).
     *
     * @param kind      Art des Fakts, wie der Parser sie nennt (etwa {@code CONTROL}, {@code CALL}, {@code TRANSFER})
     * @param text      Wortlaut des Fakts
     * @param site      Index der Codestelle in {@link #sites()}, in der er liegt
     * @param firstLine erste Zeile ab 1
     * @param lastLine  letzte Zeile (einschließlich)
     */
    public record ParserFact(String kind, String text, int site, int firstLine, int lastLine) {

        public ParserFact {
            kind = requireText(kind, "kind");
            text = requireText(text, "text");
            if (site < 0) {
                throw new IllegalArgumentException("Codestelle " + site + " ungültig");
            }
            if (firstLine < 1 || lastLine < firstLine) {
                throw new IllegalArgumentException("Zeilen " + firstLine + ".." + lastLine + " ungültig");
            }
        }

        /** Ein Parserfakt ist immer ein technischer Fakt, nie selbst eine fachliche Erkenntnis. */
        public InsightLevel level() {
            return InsightLevel.PARSER_FACT;
        }
    }

    /**
     * Eine maßgebliche Abhängigkeit (ADR 0041, Punkt 10): ein aufgelöstes Copybook mit Dokument und Revision oder eine
     * negative Probe (gesucht, nicht gefunden). Findet sich eine negative Probe später, ist der Beleg möglicherweise
     * veraltet.
     *
     * @param name     Name, unter dem gesucht wurde (etwa der Copybook-Name)
     * @param document aufgelöstes Dokument; leer bei einer negativen Probe
     * @param revision Revision des aufgelösten Dokuments; leer bei einer negativen Probe
     */
    public record Dependency(String name, Optional<DocumentKey> document, Optional<String> revision) {

        public Dependency {
            name = requireText(name, "name");
            document = document == null ? Optional.empty() : document;
            revision = revision == null ? Optional.empty() : revision;
            if (document.isPresent() != revision.isPresent()) {
                throw new IllegalArgumentException("Eine aufgelöste Abhängigkeit braucht Dokument und Revision");
            }
            revision.ifPresent(value -> requireText(value, "revision"));
        }

        /** Aufgelöst: Dokument und Revision. */
        public static Dependency resolved(String name, DocumentKey document, String revision) {
            return new Dependency(name, Optional.of(Objects.requireNonNull(document, "document")),
                    Optional.of(Objects.requireNonNull(revision, "revision")));
        }

        /** Negative Probe: gesucht, nicht gefunden. */
        public static Dependency missing(String name) {
            return new Dependency(name, Optional.empty(), Optional.empty());
        }

        /** Ob dies eine negative Probe ist. */
        public boolean negativeProbe() {
            return document.isEmpty();
        }
    }
}
