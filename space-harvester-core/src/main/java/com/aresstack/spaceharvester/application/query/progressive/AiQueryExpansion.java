package com.aresstack.spaceharvester.application.query.progressive;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Varianten über ein Modell des KI-Dienstes (ADR 0017): Das Modell formuliert dieselbe Frage nach abnehmender
 * Genauigkeit um, mit möglichst vielen Begriffen des Nutzers zuerst und schrittweise weniger, als JSON
 * {@code {"queries":[{"text":…,"priority":1},…]}}. Die Antwort wird streng geprüft: nur Suchbegriffe (keine Abfragesprache
 * wie CQL, keine Operatoren), begrenzte Länge und Anzahl, Dubletten und die ursprüngliche Anfrage fallen weg.
 *
 * <p>Ohne Modell, bei einem Fehler, einer Zeitüberschreitung oder einer unbrauchbaren Antwort liefert der Rückfall die
 * Varianten ({@link RuleBasedExpansion}); die Suche scheitert nie daran. An den Dienst geht nur die Frage des Nutzers,
 * nie Inhalt einer Quelle; ins Log kommt sie nie.
 */
public final class AiQueryExpansion implements QueryExpansionStrategy {

    private static final System.Logger LOG = System.getLogger(AiQueryExpansion.class.getName());
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Unter der Frist einer Variante ({@link SearchExecutionPolicy#DEFAULT_VARIANT_TIMEOUT}), damit der Rückfall greift. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(6);
    /** Längste Variante in Zeichen. */
    static final int MAX_VARIANT_CHARS = 200;
    /** Längste Antwort, die überhaupt gelesen wird. */
    static final int MAX_ANSWER_CHARS = 64 * 1024;
    /** Längste Frage, die an den Dienst geht. */
    static final int MAX_QUESTION_CHARS = 2000;

    /** Zeichen und Wörter einer Abfragesprache: Eine solche Variante wird verworfen, nicht repariert. */
    private static final Pattern QUERY_SYNTAX = Pattern.compile(
            "[\"'`(){}\\[\\]=~<>:*?\\\\|&!^+]|(?<![\\p{L}\\p{N}])(AND|OR|NOT)(?![\\p{L}\\p{N}])"
                    + "|(?i)(?<![\\p{L}\\p{N}])(cql|sitesearch|space\\s*=|type\\s*=|text\\s*~|title\\s*~)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    static final String INSTRUCTIONS = """
            Du formulierst Suchanfragen für eine Schlagwortsuche in einem Unternehmenswiki.
            Aus der Frage des Nutzers erzeugst du Varianten DERSELBEN Frage, nach abnehmender Genauigkeit geordnet:
            - Die erste Variante enthält möglichst viele der wichtigen Begriffe des Nutzers, ohne Füllwörter.
            - Jede weitere Variante lässt den unwichtigsten verbliebenen Begriff weg oder ersetzt ihn durch ein \
            gängiges Synonym, sodass die Treffer langsam mehr werden.
            - Begriffe des Nutzers (Namen, Kennungen, Fachwörter) behältst du unverändert bei.
            - Keine neuen Themen, keine Unterfragen, keine Erklärungen.
            - Nur Suchbegriffe, durch Leerzeichen getrennt: keine Abfragesprache wie CQL, keine Operatoren (AND, OR, \
            NOT), keine Anführungszeichen, Klammern, Doppelpunkte oder Platzhalter.
            - Höchstens %d Varianten.
            Antworte ausschließlich mit einem JSON-Objekt dieser Form und sonst nichts:
            {"queries":[{"text":"Begriff1 Begriff2 Begriff3","priority":1},{"text":"Begriff1 Begriff2","priority":2}]}
            Priorität 1 ist die genaueste Variante.""";

    private final Supplier<Optional<QueryVariantModel>> model;
    private final QueryExpansionStrategy fallback;
    private final Duration timeout;
    private final ExecutorService calls;

    /**
     * @param model    das Modell der wirksamen Konfiguration, leer ohne Chat-Modell; wird je Suche gefragt
     * @param fallback Varianten ohne Modell, etwa {@link RuleBasedExpansion}
     * @param timeout  so lange wartet die Suche auf das Modell
     */
    public AiQueryExpansion(Supplier<Optional<QueryVariantModel>> model, QueryExpansionStrategy fallback,
                            Duration timeout) {
        this.model = Objects.requireNonNull(model, "model");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout muss positiv sein");
        }
        this.calls = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("query-variants-", 0).factory());
    }

    @Override
    public List<String> expand(String original, int maxVariants) {
        if (maxVariants <= 0) {
            return List.of();
        }
        Optional<QueryVariantModel> current;
        try {
            current = model.get();
        } catch (RuntimeException e) {
            current = Optional.empty();
        }
        if (current.isEmpty() || original == null || original.isBlank()) {
            return fallback.expand(original, maxVariants);
        }
        QueryVariantModel chosen = current.get();
        String question = original.strip();
        if (question.length() > MAX_QUESTION_CHARS) {
            question = question.substring(0, MAX_QUESTION_CHARS);
        }
        String asked = question;
        Future<String> answer = calls.submit(() -> chosen.complete(INSTRUCTIONS.formatted(maxVariants), asked));
        try {
            List<String> variants = parse(answer.get(timeout.toNanos(), TimeUnit.NANOSECONDS), original,
                    maxVariants);
            if (!variants.isEmpty()) {
                return variants;
            }
            LOG.log(System.Logger.Level.INFO, "Modell lieferte keine brauchbaren Varianten; Regeln übernehmen");
        } catch (TimeoutException e) {
            answer.cancel(true);
            LOG.log(System.Logger.Level.INFO, "Modell antwortete nicht in {0} ms; Regeln übernehmen",
                    timeout.toMillis());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            // Nur der Typ: Meldungen eines Dienstes können Endpunkte nennen, nie aber die Frage ins Log.
            LOG.log(System.Logger.Level.INFO, "Modell nicht erreichbar ({0}); Regeln übernehmen",
                    cause.getClass().getName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            answer.cancel(true);
            return List.of();
        }
        return fallback.expand(original, maxVariants);
    }

    /**
     * Die gültigen Varianten der Antwort, nach Priorität (bei Gleichstand in Antwortreihenfolge); leer, wenn die
     * Antwort kein passendes JSON enthält. Verworfen werden Texte mit Abfragesyntax, zu lange, leere, doppelte (ohne
     * Groß- und Kleinschreibung und Leerraum) und die ursprüngliche Anfrage selbst.
     */
    static List<String> parse(String answer, String original, int maxVariants) {
        if (answer == null || answer.length() > MAX_ANSWER_CHARS) {
            return List.of();
        }
        String json = firstObject(answer);
        if (json == null) {
            return List.of();
        }
        JsonNode queries;
        try {
            queries = JSON.readTree(json).get("queries");
        } catch (Exception e) {
            return List.of();
        }
        if (queries == null || !queries.isArray()) {
            return List.of();
        }
        record Candidate(String text, int priority, int order) {
        }
        List<Candidate> candidates = new ArrayList<>();
        int order = 0;
        for (JsonNode entry : queries) {
            JsonNode text = entry.isString() ? entry : entry.get("text");
            if (text == null || !text.isString()) {
                continue;
            }
            JsonNode priority = entry.isObject() ? entry.get("priority") : null;
            int rank = priority != null && priority.canConvertToInt() && priority.asInt() >= 1 ? priority.asInt()
                    : Integer.MAX_VALUE;
            candidates.add(new Candidate(text.asString(), rank, order++));
        }
        candidates.sort(Comparator.comparingInt(Candidate::priority).thenComparingInt(Candidate::order));
        Set<String> seen = new HashSet<>();
        if (original != null) {
            seen.add(normalize(original));
        }
        List<String> variants = new ArrayList<>();
        for (Candidate candidate : candidates) {
            String text = WHITESPACE.matcher(candidate.text().strip()).replaceAll(" ");
            if (text.isEmpty() || text.length() > MAX_VARIANT_CHARS || QUERY_SYNTAX.matcher(text).find()
                    || !seen.add(normalize(text))) {
                continue;
            }
            variants.add(text);
            if (variants.size() == maxVariants) {
                break;
            }
        }
        return List.copyOf(variants);
    }

    /** Das erste ausgeglichene JSON-Objekt, auch hinter Text oder in einem Codeblock; Zeichenketten zählen nicht mit. */
    static String firstObject(String text) {
        int start = text.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                } else if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}' && --depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    private static String normalize(String text) {
        return WHITESPACE.matcher(text.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }
}
