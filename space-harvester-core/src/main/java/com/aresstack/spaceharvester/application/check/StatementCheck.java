package com.aresstack.spaceharvester.application.check;

import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.simplification.SentenceSimplifier;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anweisung, Eingabe und strenges Lesen der Antwort des Prüfschritts (ADR 0032, FL4): Das Chat-Modell beurteilt
 * Aussagen gegen ihren Originalsatz. Rechnet nur, ruft kein Modell und speichert nichts.
 *
 * <p><b>Version 1</b> ({@code anweisung-v1.txt}) ist <b>ungemessen</b>, bis sie mit einem echten Modell auf dem
 * Prüfstand gelaufen ist. Jede Änderung an Anweisung, Eingabe oder Format ist eine neue {@link #PROMPT_VERSION}.
 *
 * <p>Eingabe je Aufruf: eine Zeile {@code Satz: <Originalsatz>}, dann je Aussage {@code <Nummer> <Aussage>}, die
 * Nummern ab 1 ({@link #render}). Enthält der Aufruf eine Aussage der Vorstufe, stehen davor unter
 * {@code Zusammenhang:} die Zeilen ihres Abschnitts, je mit {@code > } (derselbe Abschnitt wie bei der Vorstufe):
 * Eine Vorstufen-Aussage darf Beteiligte aus Nachbarzeilen tragen, etwa den Handelnden eines Stichpunkts aus der Zeile
 * darüber. Korrekturen werden dann gegen Satz und Zusammenhang abgeglichen. Der Seitentext ist Material, keine Anweisung: Er geht nur als Benutzernachricht
 * hinaus, und von der Antwort zählt nur, was dem Format genau entspricht.
 *
 * <p>Lesen der Antwort:
 * <ul>
 *     <li>Eine Zeile gilt nur, wenn sie genau {@code <Nummer>|STIMMT}, {@code <Nummer>|FALSCH},
 *     {@code <Nummer>|UNVOLLSTAENDIG} oder {@code <Nummer>|UNVOLLSTAENDIG|<Korrektur>} ist und die Nummer zu einer
 *     Aussage des Aufrufs gehört; alles andere wird gezählt und verworfen. Zwei verschiedene Urteile zur selben Nummer
 *     heben sich auf (keins gilt), eine Wiederholung desselben Urteils zählt einmal.</li>
 *     <li>Korrektur: ein Satz bis {@value #MAX_CORRECTION_CHARS} Zeichen, ohne {@code |}, der den Wortabgleich der
 *     Vorstufe gegen den Originalsatz besteht ({@link SentenceSimplifier#derivable}): jedes Inhaltswort, jede
 *     Ziffernfolge, jede Verneinung und jedes Modalverb steht im Satz. Sonst gilt das Urteil ohne Korrektur.
 *     Ob die Korrektur danach als Aussage taugt (Verb, Verneinung, Handelnder), entscheidet {@link Corrections}.</li>
 * </ul>
 */
public final class StatementCheck {

    /** Version von Anweisung, Eingabe und Antwortformat; Teil des Schlüssels der Ablage. */
    public static final String PROMPT_VERSION = "1";
    /** Längste Korrektur, die übernommen wird. */
    static final int MAX_CORRECTION_CHARS = 400;

    private static final String INSTRUCTIONS = resource("anweisung-v1.txt");
    private static final Pattern LINE = Pattern.compile("(\\d{1,4})\\|(STIMMT|UNVOLLSTAENDIG|FALSCH)(?:\\|(.*))?");

    private StatementCheck() {
    }

    /** Ein Beteiligter einer Aussage, wie er dem Modell gezeigt wird: Rolle, Präposition und Wortlaut. */
    public record Participant(ParsedStatements.Role role, String marker, String text) {

        public Participant {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Eine Aussage, wie sie dem Modell gezeigt wird.
     *
     * @param predicate    Grundform des Verbs oder Adjektiv/Nomen des Zustands
     * @param state        ob es eine Zustandsaussage ist
     * @param participants Beteiligte in Reihenfolge der Aussage
     * @param features     Merkmale; unbekannte werden nicht gezeigt
     */
    public record Item(String predicate, boolean state, List<Participant> participants,
                       ParsedStatements.Features features) {

        public Item {
            Objects.requireNonNull(predicate, "predicate");
            participants = List.copyOf(participants);
            features = features == null ? ParsedStatements.Features.UNKNOWN : features;
        }

        /** Die Aussage aus einer Analyse; Beteiligte mit dem Wortlaut ihrer Erwähnung. */
        public static Item of(ParsedStatements.Statement statement, Map<String, ParsedStatements.Mention> mentions) {
            List<Participant> participants = new ArrayList<>();
            for (ParsedStatements.Argument argument : statement.arguments()) {
                ParsedStatements.Mention mention = mentions.get(argument.mention());
                if (mention != null) {
                    participants.add(new Participant(argument.role(), argument.marker(), mention.text()));
                }
            }
            String predicate = statement.predicateLemma() != null ? statement.predicateLemma()
                    : statement.predicateText();
            return new Item(predicate, statement.state(), participants, statement.features());
        }
    }

    /**
     * Ein gelesenes Urteil.
     *
     * @param verdict    das Urteil
     * @param correction die Korrektur, wenn sie den Wortabgleich bestand; sonst {@code null}
     */
    public record Answer(ParsedStatements.Verdict verdict, String correction) {

        public Answer {
            Objects.requireNonNull(verdict, "verdict");
            if (correction != null && verdict != ParsedStatements.Verdict.UNVOLLSTAENDIG) {
                throw new IllegalArgumentException("Nur UNVOLLSTAENDIG hat eine Korrektur");
            }
        }
    }

    /**
     * Ergebnis von {@link #parse}.
     *
     * @param answers            je Nummer (ab 1) das Urteil; fehlende Nummern blieben ohne gültiges Urteil
     * @param rejected           Zeilen außerhalb des Formats oder zu einer fremden Nummer
     * @param conflicting        Nummern mit zwei verschiedenen Urteilen; keins gilt
     * @param correctionsRejected Korrekturen, die den Wortabgleich oder die Form nicht bestanden; ihr Urteil gilt ohne sie
     */
    public record Parsed(Map<Integer, Answer> answers, int rejected, int conflicting, int correctionsRejected) {
    }

    /** Die Anweisung der aktuellen {@link #PROMPT_VERSION}, unverändert. */
    public static String instructions() {
        return INSTRUCTIONS;
    }

    /** Die Eingabe eines Aufrufs ohne Zusammenhang: der Originalsatz, dann die Aussagen nummeriert ab 1. */
    public static String input(String sentence, List<Item> items) {
        return input(sentence, List.of(), items);
    }

    /**
     * Die Eingabe eines Aufrufs: der Zusammenhang (falls vorhanden), der Originalsatz, dann die Aussagen nummeriert
     * ab 1.
     *
     * @param context die Zeilen des Abschnitts in Textreihenfolge, leer ohne Zusammenhang
     */
    public static String input(String sentence, List<String> context, List<Item> items) {
        StringBuilder input = new StringBuilder();
        if (!context.isEmpty()) {
            input.append("Zusammenhang:\n");
            context.forEach(line -> input.append("> ").append(oneLine(line)).append('\n'));
        }
        input.append("Satz: ").append(oneLine(sentence)).append('\n');
        int number = 1;
        for (Item item : items) {
            input.append(number++).append(' ').append(render(item)).append('\n');
        }
        return input.toString();
    }

    /**
     * Eine Aussage als Zeile: {@code Verb: prüfen; Handelnder: die Stelle; Betroffenes: den Antrag; verneint: ja;
     * Verbindlichkeit: muss; Zeitbezug: gilt}. Bei einem Zustand {@code Zustand: zuständig; Träger: …}. Unbekannte
     * Merkmale fehlen.
     */
    public static String render(Item item) {
        StringBuilder line = new StringBuilder(item.state() ? "Zustand: " : "Verb: ").append(oneLine(item.predicate()));
        for (Participant participant : item.participants()) {
            line.append("; ").append(label(participant.role()));
            if (participant.role() == ParsedStatements.Role.PREPOSITIONAL_OBJECT && participant.marker() != null) {
                line.append(" mit „").append(oneLine(participant.marker())).append('“');
            }
            line.append(": ").append(oneLine(participant.text()));
        }
        ParsedStatements.Features features = item.features();
        if (features.negation() != ParsedStatements.Negation.UNKNOWN) {
            line.append("; verneint: ").append(features.negation() == ParsedStatements.Negation.NEGATED ? "ja"
                    : "nein");
        }
        if (features.modality() != ParsedStatements.Modality.UNKNOWN) {
            line.append("; Verbindlichkeit: ").append(switch (features.modality()) {
                case NONE -> "keine";
                case MUSS -> "muss";
                case SOLL -> "soll";
                case KANN -> "kann";
                case DARF -> "darf";
                case UNKNOWN -> throw new IllegalStateException();
            });
        }
        if (features.temporality() != ParsedStatements.Temporality.UNKNOWN) {
            line.append("; Zeitbezug: ").append(switch (features.temporality()) {
                case GILT -> "gilt";
                case GEPLANT -> "geplant";
                case FRUEHER -> "früher";
                case VERWORFEN -> "verworfen";
                case UNKNOWN -> throw new IllegalStateException();
            });
        }
        return line.toString();
    }

    private static String label(ParsedStatements.Role role) {
        return switch (role) {
            case ACTOR -> "Handelnder";
            case AFFECTED_OBJECT -> "Betroffenes";
            case RECIPIENT -> "Empfänger";
            case PREPOSITIONAL_OBJECT -> "Ergänzung";
            case INSTRUMENT -> "Mittel";
            case TIME -> "Zeit";
            case CIRCUMSTANCE -> "Umstand";
            case BEARER -> "Träger";
        };
    }

    /**
     * Liest die Antwort zu einem Aufruf streng.
     *
     * @param sentence der Originalsatz des Aufrufs, gegen den Korrekturen abgeglichen werden
     * @param count    Zahl der Aussagen im Aufruf
     */
    public static Parsed parse(String sentence, int count, String response) {
        return parse(sentence, List.of(), count, response);
    }

    /**
     * Liest die Antwort zu einem Aufruf mit Zusammenhang streng; Korrekturen werden gegen Satz und Zusammenhang
     * abgeglichen ({@link #source}).
     */
    public static Parsed parse(String sentence, List<String> context, int count, String response) {
        String source = source(sentence, context);
        Map<Integer, Answer> answers = new LinkedHashMap<>();
        Set<Integer> conflicts = new HashSet<>();
        int rejected = 0;
        int correctionsRejected = 0;
        for (String raw : (response == null ? "" : response).split("\\r?\\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = LINE.matcher(line);
            if (!matcher.matches()) {
                rejected++;
                continue;
            }
            int number = Integer.parseInt(matcher.group(1));
            ParsedStatements.Verdict verdict = ParsedStatements.Verdict.valueOf(matcher.group(2));
            String correction = matcher.group(3);
            if (number < 1 || number > count) {
                rejected++;
                continue;
            }
            if (correction != null && verdict != ParsedStatements.Verdict.UNVOLLSTAENDIG) {
                rejected++; // Korrektur nur bei UNVOLLSTAENDIG; die Zeile ist außerhalb des Formats
                continue;
            }
            String accepted = null;
            if (correction != null) {
                String text = correction.replaceAll("\\s+", " ").strip();
                if (!text.isEmpty() && text.length() <= MAX_CORRECTION_CHARS && !text.contains("|")
                        && SentenceSimplifier.derivable(source, text)) {
                    accepted = text;
                } else {
                    correctionsRejected++;
                }
            }
            if (conflicts.contains(number)) {
                continue;
            }
            Answer previous = answers.get(number);
            if (previous == null) {
                answers.put(number, new Answer(verdict, accepted));
            } else if (previous.verdict() != verdict) {
                answers.remove(number);
                conflicts.add(number);
            } else if (previous.correction() == null && accepted != null) {
                answers.put(number, new Answer(verdict, accepted));
            }
        }
        return new Parsed(java.util.Collections.unmodifiableMap(answers), rejected, conflicts.size(),
                correctionsRejected);
    }

    /** Der Text, gegen den Korrekturen abgeglichen werden: der Satz und, falls vorhanden, sein Zusammenhang. */
    public static String source(String sentence, List<String> context) {
        if (context.isEmpty()) {
            return sentence;
        }
        StringBuilder source = new StringBuilder(sentence);
        context.forEach(line -> source.append('\n').append(line));
        return source.toString();
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private static String resource(String name) {
        try (InputStream input = StatementCheck.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException(name + " fehlt");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
