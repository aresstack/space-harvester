package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Argument;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Basis;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Features;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Mention;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.MentionKind;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Modality;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Negation;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Priority;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Role;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Statement;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Temporality;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Erfundene Aussagen für die Tests des Stands (ADR 0032, FL6): von Hand gebaut wie aus einem Schein-Analysator, ohne
 * Parser und ohne Modell. Die Sätze stehen nur als Wortlaut der Erwähnungen da.
 */
final class StandingFixtures {

    static final Instant AT = Instant.parse("2026-11-17T08:00:00Z");

    private StandingFixtures() {
    }

    /** Eine Seite im Bau: Erwähnungen und Aussagen werden angehängt. */
    static final class PageBuilder {

        final PageKey key;
        final String spaceKey;
        final String title;
        final String input;
        final List<Mention> mentions = new ArrayList<>();
        final List<Statement> statements = new ArrayList<>();
        final List<ParsedStatements.Sentence> sentences = new ArrayList<>();
        PageStatements.State state = PageStatements.State.CURRENT;

        PageBuilder(String pageId, String title, char hash) {
            this.key = new PageKey("wiki", pageId);
            this.spaceKey = "FOE";
            this.title = title;
            this.input = "sha256:" + String.valueOf(hash).repeat(64);
        }

        String prefix() {
            return PageStatementAnalysis.documentId(key, input);
        }

        /** Handlung: Handelnder, Verb, Betroffener. */
        PageBuilder action(String actor, String verb, String object, Features features) {
            return add(actor, verb, object, features, false, ParsedStatements.Quality.PLAUSIBLE,
                    ParsedStatements.Review.UNREVIEWED, null);
        }

        /** Zustand: Träger, Eigenschaft. */
        PageBuilder state(String bearer, String property, Features features) {
            return add(bearer, property, null, features, true, ParsedStatements.Quality.PLAUSIBLE,
                    ParsedStatements.Review.UNREVIEWED, null);
        }

        PageBuilder add(String subject, String predicate, String object, Features features, boolean state,
                        ParsedStatements.Quality quality, ParsedStatements.Review review,
                        ParsedStatements.Derivation derivation) {
            int sentence = statements.size();
            String subjectId = prefix() + "#s" + sentence + ".t1";
            mentions.add(new Mention(subjectId, sentence, 0, subject.length(), "der " + subject, subject, subject,
                    MentionKind.NOUN, false, Set.of("Nom")));
            List<Argument> arguments = new ArrayList<>();
            arguments.add(new Argument(state ? Role.BEARER : Role.ACTOR, subjectId, "nsubj", null, Priority.CORE,
                    Basis.PARSER, true));
            if (object != null) {
                String objectId = prefix() + "#s" + sentence + ".t3";
                mentions.add(new Mention(objectId, sentence, 0, object.length(), "den " + object, object, object,
                        MentionKind.NOUN, false, Set.of("Acc")));
                arguments.add(new Argument(Role.AFFECTED_OBJECT, objectId, "obj", null, Priority.CORE, Basis.PARSER,
                        true));
            }
            statements.add(new Statement(prefix() + "#s" + sentence + ".v2", sentence, 10, 10 + predicate.length(),
                    predicate, predicate, ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, !state,
                    arguments, state ? Set.of(Statement.STATE_PROPERTY) : Set.of(), 0, quality,
                    quality == ParsedStatements.Quality.PLAUSIBLE ? List.of() : List.of("NO_PARTICIPANT"), review,
                    features, derivation));
            return this;
        }

        /** Ändert die zuletzt angehängte Aussage, etwa Satzart, Befunde oder Herkunft. */
        PageBuilder edit(java.util.function.UnaryOperator<Statement> change) {
            statements.set(statements.size() - 1, change.apply(statements.getLast()));
            return this;
        }

        /** Hängt der zuletzt angehängten Aussage einen weiteren Beteiligten an. */
        PageBuilder with(Role role, String marker, String word) {
            Statement last = statements.getLast();
            String id = prefix() + "#s" + last.sentence() + ".t" + (10 + last.arguments().size());
            mentions.add(new Mention(id, last.sentence(), 0, word.length(), word, word, word, MentionKind.NOUN, false,
                    Set.of()));
            List<Argument> arguments = new ArrayList<>(last.arguments());
            arguments.add(new Argument(role, id, "obl", marker, Priority.CORE, Basis.PARSER, true));
            return edit(statement -> copy(statement, arguments, statement.findings(), statement.clause(),
                    statement.derivation()));
        }

        /** Hängt eine weitere Aussage im selben Satz wie die letzte an (etwa die Vorstufe zum selben Satz). */
        PageBuilder sameSentence(String subject, String predicate, String object, Features features,
                                 ParsedStatements.Derivation derivation) {
            int sentence = statements.getLast().sentence();
            add(subject, predicate, object, features, false, ParsedStatements.Quality.PLAUSIBLE,
                    ParsedStatements.Review.UNREVIEWED, derivation);
            Statement added = statements.getLast();
            return edit(statement -> new Statement(statement.id() + "b", sentence, statement.start(),
                    statement.end(), statement.predicateText(), statement.predicateLemma(), statement.voice(),
                    statement.clause(), statement.core(), statement.arguments(), statement.findings(),
                    statement.paragraph(), statement.quality(), statement.qualityReasons(), statement.review(),
                    statement.features(), added.derivation()));
        }

        KnowledgeGraph.AnalysedPage build() {
            ParsedStatements result = new ParsedStatements(sentences, mentions, statements, List.of(),
                    List.of("parser=schein"), null);
            PageStatementAnalysis analysis = new PageStatementAnalysis(key, spaceKey, title, "1", input, "schein",
                    "1", AT, result);
            return new KnowledgeGraph.AnalysedPage(new KnowledgeGraph.PageRef(key, spaceKey, title, state,
                    state == PageStatements.State.STALE ? PageStatements.TEXT_CHANGED : null), analysis);
        }
    }

    static Statement copy(Statement statement, List<Argument> arguments, Set<String> findings,
                          ParsedStatements.Clause clause, ParsedStatements.Derivation derivation) {
        return new Statement(statement.id(), statement.sentence(), statement.start(), statement.end(),
                statement.predicateText(), statement.predicateLemma(), statement.voice(), clause, statement.core(),
                arguments, findings, statement.paragraph(), statement.quality(), statement.qualityReasons(),
                statement.review(), statement.features(), derivation);
    }

    static PageBuilder page(String pageId, String title, char hash) {
        return new PageBuilder(pageId, title, hash);
    }

    static Features features(Temporality temporality) {
        return new Features(Negation.AFFIRMED, Modality.NONE, temporality);
    }

    static Features features(Negation negation, Modality modality, Temporality temporality) {
        return new Features(negation, modality, temporality);
    }

    static PageHeader header(String pageId, String title, String parentPageId, Map<String, String> attributes) {
        return PageHeader.of("wiki", new PageMetadata(pageId, "confluence:wiki/page/" + pageId, title, "FOE",
                parentPageId, "1", AT, null, PageMetadata.sorted(attributes)), AT);
    }
}
