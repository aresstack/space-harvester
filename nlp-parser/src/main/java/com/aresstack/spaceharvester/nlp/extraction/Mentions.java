package com.aresstack.spaceharvester.nlp.extraction;

import com.aresstack.spaceharvester.nlp.syntax.DocumentRef;
import com.aresstack.spaceharvester.nlp.syntax.MultiwordToken;
import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Bildet die Erwähnungen eines Dokuments: jedes Nomen, jeder Eigenname und jedes Pronomen, das nicht selbst Teil
 * einer größeren Nominalgruppe ist ({@code compound}, {@code flat}), wird mit Artikeln, Adjektiven, Zahlen und
 * Namensteilen zu einer Erwähnung. Präpositionen ({@code case}), Attribute ({@code nmod}), Relativsätze und
 * Koordination gehören nicht dazu.
 */
final class Mentions {

    /** Relationen, deren Tokens zur Nominalgruppe ihres Kopfs gehören. */
    private static final Set<String> GROUP = Set.of(
            "det", "det:poss", "amod", "compound", "flat", "nummod");
    private static final Set<String> NOMINAL = Set.of("NOUN", "PROPN", "PRON");

    private final Map<String, Mention> byId = new LinkedHashMap<>();
    private final Map<Long, Mention> byToken = new LinkedHashMap<>();

    private Mentions() {
    }

    static Mentions of(ParsedDocument document) {
        Mentions mentions = new Mentions();
        for (Sentence sentence : document.sentences()) {
            for (Token token : sentence.tokens()) {
                if (token.hasUpos(NOMINAL.toArray(String[]::new)) && !partOfGroup(sentence, token)) {
                    mentions.add(sentence.index(), build(document, sentence, token));
                }
            }
        }
        return mentions;
    }

    /** Erwähnung mit Kopf {@code token} in Satz {@code sentence}; legt für nicht nominale Köpfe eine an. */
    Mention at(ParsedDocument document, Sentence sentence, Token token) {
        Mention mention = byToken.get(key(sentence.index(), token.id()));
        if (mention == null) {
            mention = build(document, sentence, token);
            add(sentence.index(), mention);
        }
        return mention;
    }

    /**
     * Teilsatz mit Kopf {@code head} als Erwähnung der Art {@link Mention.Kind#CLAUSE}: der ganze Teilbaum ohne
     * Satzzeichen am Rand, Grundform = Grundform des Kopfs. Eine vorhandene Erwähnung am selben Kopf bleibt.
     */
    Mention clause(ParsedDocument document, Sentence sentence, Token head) {
        Mention existing = byToken.get(key(sentence.index(), head.id()));
        if (existing != null && existing.kind() == Mention.Kind.CLAUSE) {
            return existing;
        }
        Set<Integer> ids = new TreeSet<>();
        collectGroup(sentence, head.id(), ids);
        List<Integer> ordered = new ArrayList<>(ids);
        while (ordered.size() > 1 && "punct".equals(sentence.token(ordered.get(ordered.size() - 1)).deprel())) {
            ordered.remove(ordered.size() - 1);
        }
        int start = sentence.token(ordered.get(0)).start();
        int end = sentence.token(ordered.get(ordered.size() - 1)).end();
        Mention mention = new Mention(id(document.ref(), sentence.index(), head.id()), sentence.index(), head.id(),
                ordered, start, end, document.text().substring(start, end), head.form(), head.lemma(),
                Mention.Kind.CLAUSE, Resolution.SURFACE, Set.of(), null);
        add(sentence.index(), mention);
        return mention;
    }

    Mention get(String id) {
        return byId.get(id);
    }

    List<Mention> all() {
        return List.copyOf(byId.values());
    }

    private void add(int sentence, Mention mention) {
        byId.put(mention.id(), mention);
        byToken.put(key(sentence, mention.head()), mention);
    }

    private static long key(int sentence, int token) {
        return ((long) sentence << 32) | token;
    }

    private static boolean partOfGroup(Sentence sentence, Token token) {
        return ("compound".equals(token.deprel()) || "flat".equals(token.baseRelation()))
                && sentence.head(token) != null && sentence.head(token).hasUpos("NOUN", "PROPN");
    }

    static String id(DocumentRef ref, int sentence, int token) {
        return ref.key() + "#s" + (sentence + 1) + ".t" + token;
    }

    private static Mention build(ParsedDocument document, Sentence sentence, Token head) {
        Set<Integer> ids = new TreeSet<>();
        collect(sentence, head.id(), ids);
        int start = Integer.MAX_VALUE;
        int end = 0;
        for (int id : ids) {
            Token token = sentence.token(id);
            MultiwordToken multiword = sentence.multiwordToken(id);
            if (multiword != null && !ids.containsAll(range(multiword))) {
                // Artikel aus einer Verschmelzung (am = an + dem): die Spanne gehört zur Präposition
                continue;
            }
            start = Math.min(start, token.start());
            end = Math.max(end, token.end());
        }
        if (start > end) {
            start = head.start();
            end = head.end();
        }
        Mention.Kind kind = switch (head.upos() == null ? "" : head.upos()) {
            case "NOUN" -> Mention.Kind.NOUN;
            case "PROPN" -> Mention.Kind.PROPER_NOUN;
            case "PRON" -> Mention.Kind.PRONOUN;
            default -> Mention.Kind.OTHER;
        };
        Set<String> cases = new HashSet<>();
        Basis caseBasis = null;
        if (head.feature("Case") != null) {
            cases.addAll(List.of(head.feature("Case").split(",")));
            caseBasis = Basis.PARSER;
        } else {
            for (Token token : hintTokens(sentence, head, kind)) {
                Set<String> hint = CaseHints.of(token.form());
                if (!hint.isEmpty()) {
                    if (cases.isEmpty()) {
                        cases.addAll(hint);
                    } else {
                        cases.retainAll(hint);
                    }
                    caseBasis = Basis.CASE_HEURISTIC;
                }
            }
        }
        // Mehrteilige Namen („Batch-Job“, „Load Balancer“): unmittelbar angrenzende compound-/flat-Teile gehören
        // zum Kopf (ADR 0032); Form aus dem Originaltext, Grundform mit der Grundform des Kopfs.
        int first = head.id();
        int last = head.id();
        while (first > 1 && namePart(sentence, sentence.token(first - 1), head, true)) {
            first--;
        }
        while (last < sentence.tokens().size() && namePart(sentence, sentence.token(last + 1), head, false)) {
            last++;
        }
        String headForm = head.form();
        String headLemma = head.lemma();
        if ((first != head.id() || last != head.id()) && sentence.multiwordToken(first) == null
                && sentence.multiwordToken(last) == null) {
            String text = document.text();
            headForm = text.substring(sentence.token(first).start(), sentence.token(last).end());
            if (headLemma != null) {
                headLemma = text.substring(sentence.token(first).start(), head.start()).toLowerCase(Locale.ROOT)
                        + headLemma + text.substring(head.end(), sentence.token(last).end()).toLowerCase(Locale.ROOT);
            }
        }
        return new Mention(id(document.ref(), sentence.index(), head.id()), sentence.index(), head.id(),
                new ArrayList<>(ids), start, end, document.text().substring(start, end), headForm, headLemma,
                kind, kind == Mention.Kind.PRONOUN ? Resolution.UNRESOLVED : Resolution.SURFACE, cases,
                cases.isEmpty() ? null : caseBasis);
    }

    /**
     * Ob {@code token} direkt neben dem Namen von {@code head} ein Namensteil ist: davor ein {@code compound} am Kopf
     * mit Bindestrich oder dieser Bindestrich, dahinter ein {@code flat} am Kopf.
     */
    private static boolean namePart(Sentence sentence, Token token, Token head, boolean before) {
        if (before) {
            if ("-".equals(token.form()) && token.id() > 1) {
                Token previous = sentence.token(token.id() - 1);
                return "compound".equals(previous.deprel()) && previous.head() == head.id();
            }
            // Nur mit Bindestrich („Batch-Job“); ohne ihn hängt der Parser auch Adjektive als compound an.
            return "compound".equals(token.deprel()) && token.head() == head.id() && token.hasUpos("NOUN", "PROPN")
                    && "-".equals(sentence.token(token.id() + 1).form());
        }
        return "flat".equals(token.baseRelation()) && token.head() == head.id() && token.hasUpos("NOUN", "PROPN", "X");
    }

    private static List<Integer> range(MultiwordToken multiword) {
        List<Integer> ids = new ArrayList<>();
        for (int id = multiword.first(); id <= multiword.last(); id++) {
            ids.add(id);
        }
        return ids;
    }

    private static List<Token> hintTokens(Sentence sentence, Token head, Mention.Kind kind) {
        List<Token> result = new ArrayList<>();
        if (kind == Mention.Kind.PRONOUN) {
            result.add(head);
        }
        for (Token dependent : sentence.dependents(head.id())) {
            if ("det".equals(dependent.baseRelation())) {
                result.add(dependent);
            }
        }
        return result;
    }

    private static void collect(Sentence sentence, int id, Set<Integer> ids) {
        ids.add(id);
        for (Token dependent : sentence.dependents(id)) {
            if (GROUP.contains(dependent.deprel()) || GROUP.contains(dependent.baseRelation())) {
                collectGroup(sentence, dependent.id(), ids);
            }
        }
    }

    /** Ein Gruppenteil mit allem, was an ihm hängt, außer Satzzeichen ({@code sehr gute Beratung}). */
    private static void collectGroup(Sentence sentence, int id, Set<Integer> ids) {
        ids.add(id);
        for (Token dependent : sentence.dependents(id)) {
            if (!"punct".equals(dependent.deprel())) {
                collectGroup(sentence, dependent.id(), ids);
            }
        }
    }
}
