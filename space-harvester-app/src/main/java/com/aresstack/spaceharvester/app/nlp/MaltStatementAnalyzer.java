package com.aresstack.spaceharvester.app.nlp;

import com.aresstack.spaceharvester.application.knowledge.AnalyzerUnavailableException;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.knowledge.StatementAnalyzer;
import com.aresstack.spaceharvester.nlp.extraction.Argument;
import com.aresstack.spaceharvester.nlp.extraction.CoreRelation;
import com.aresstack.spaceharvester.nlp.extraction.ExtractionSettings;
import com.aresstack.spaceharvester.nlp.extraction.Finding;
import com.aresstack.spaceharvester.nlp.extraction.Mention;
import com.aresstack.spaceharvester.nlp.extraction.NominalAttachment;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricAnalysis;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricExtraction;
import com.aresstack.spaceharvester.nlp.extraction.Resolution;
import com.aresstack.spaceharvester.nlp.extraction.SalienceProjection;
import com.aresstack.spaceharvester.nlp.extraction.Statement;
import com.aresstack.spaceharvester.nlp.malt.ExpectedModel;
import com.aresstack.spaceharvester.nlp.malt.GermanParserModelPin;
import com.aresstack.spaceharvester.nlp.malt.MaltModelLoader;
import com.aresstack.spaceharvester.nlp.model.ModelUnavailableException;
import com.aresstack.spaceharvester.nlp.opennlp.OpenNlpGermanPreprocessor;
import com.aresstack.spaceharvester.nlp.syntax.Component;
import com.aresstack.spaceharvester.nlp.syntax.ConllU;
import com.aresstack.spaceharvester.nlp.syntax.DependencyAnalysis;
import com.aresstack.spaceharvester.nlp.syntax.DocumentRef;
import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MaltParser-Aussagenanalyse für die Desktop-App (#499): OpenNLP für Sätze, Wörter, Wortarten und Grundformen,
 * MaltParser mit dem gepinnten deutschen Modell aus #99, danach die objektzentrierte Auswertung aus ADR 0005.
 *
 * <p>Das Modell wird erst beim ersten {@link #analyze} geladen (rund 7 s, rund 225 MB Heap) und bleibt danach für die
 * laufende Sitzung geladen; {@link #version()} lädt nichts. Fehlt das Modell oder passt es nicht (Manifest, Version,
 * SHA-256), meldet der Aufruf {@link AnalyzerUnavailableException} mit Installationshinweis; nichts wird
 * heruntergeladen und es gibt keinen stillen Rückfall. Ein fehlgeschlagener Ladeversuch wird nicht gemerkt, damit ein
 * nachträglich installiertes Modell beim nächsten Klick greift. Threadsicher.
 */
public final class MaltStatementAnalyzer implements StatementAnalyzer {

    /** Kennung des Verfahrens. */
    public static final String ID = "maltparser-de";
    /** Logik dieses Adapters (Abbildung auf das Core-Modell); steigt bei jeder Änderung daran. */
    static final String ADAPTER_VERSION = "1";
    /** Systemeigenschaft für ein anderes Modellverzeichnis als {@link #defaultDirectory}. */
    public static final String DIRECTORY_PROPERTY = "spaceharvester.nlpModelDir";
    /** Release-Seite des gepinnten Modells, nur für den Hinweis; die App lädt nichts aus dem Netz. */
    static final String RELEASES = "https://github.com/aresstack/space-harvester/releases/tag/";

    private static final Logger LOG = LoggerFactory.getLogger(MaltStatementAnalyzer.class);

    private final Path directory;
    private final ExpectedModel expected;
    private final ObjectCentricExtraction extraction = new ObjectCentricExtraction(ExtractionSettings.defaults());
    private volatile DependencyAnalysis loaded;

    /**
     * @param directory Verzeichnis mit {@code model-manifest.json} und der {@code .mco} (Inhalt der Release-ZIP)
     * @param expected  das erwartete Modell; für die App {@link #pinned()}
     */
    public MaltStatementAnalyzer(Path directory, ExpectedModel expected) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.expected = Objects.requireNonNull(expected, "expected");
    }

    /** Für das gepinnte Modell aus #99. */
    public static Optional<MaltStatementAnalyzer> pinned(Path directory) {
        return GermanParserModelPin.expected().map(model -> new MaltStatementAnalyzer(directory, model));
    }

    /**
     * Wo die App das Modell erwartet: {@value #DIRECTORY_PROPERTY}, sonst
     * {@code <Datenverzeichnis>/nlp-model/<Modell>-v<Version>}.
     */
    public static Path defaultDirectory(Path dataDirectory) {
        String override = System.getProperty(DIRECTORY_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override.strip());
        }
        String tag = GermanParserModelPin.expected().map(MaltStatementAnalyzer::tag).orElse("maltparser-de");
        return dataDirectory.resolve("nlp-model").resolve(tag);
    }

    public Path directory() {
        return directory;
    }

    /** Ob das Modell in dieser Sitzung schon geladen ist. */
    public boolean loaded() {
        return loaded != null;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        String sha = expected.mcoSha256();
        return ADAPTER_VERSION + "|" + tag(expected) + "|" + sha.substring(0, Math.min(12, sha.length())) + "|rules-"
                + ObjectCentricExtraction.RULES_VERSION;
    }

    @Override
    public ParsedStatements analyze(String documentId, String text, BooleanSupplier cancelled) {
        DependencyAnalysis analysis = model();
        ParsedDocument document = analysis.analyze(ref(documentId), text, cancelled);
        ObjectCentricAnalysis result = extraction.analyze(document);
        return map(document, result);
    }

    private DependencyAnalysis model() {
        DependencyAnalysis current = loaded;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (loaded == null) {
                Path manifest = directory.resolve(MaltModelLoader.MANIFEST);
                if (!Files.isRegularFile(manifest)) {
                    throw new AnalyzerUnavailableException(AnalyzerUnavailableException.Reason.MODEL_MISSING,
                            missing(), null);
                }
                try {
                    long started = System.nanoTime();
                    loaded = new DependencyAnalysis(OpenNlpGermanPreprocessor.fromClasspath(),
                            MaltModelLoader.load(directory, expected));
                    LOG.info("Parsermodell {} geladen ({} ms)", tag(expected),
                            (System.nanoTime() - started) / 1_000_000);
                } catch (ModelUnavailableException e) {
                    LOG.warn("Parsermodell nicht nutzbar ({})", e.getClass().getSimpleName());
                    throw new AnalyzerUnavailableException(AnalyzerUnavailableException.Reason.MODEL_INVALID,
                            "Das Parsermodell unter " + directory + " passt nicht zur hinterlegten Version "
                                    + tag(expected) + ": " + e.getMessage() + ". " + install(), e);
                } catch (RuntimeException | LinkageError e) {
                    LOG.warn("Parser nicht ladbar ({})", e.getClass().getSimpleName());
                    throw new AnalyzerUnavailableException(AnalyzerUnavailableException.Reason.MODEL_INVALID,
                            "Der Parser konnte das Modell unter " + directory + " nicht laden. " + install(), e);
                }
            }
            return loaded;
        }
    }

    private String missing() {
        return "Kein Parsermodell unter " + directory + ". " + install();
    }

    private String install() {
        return "Zum Installieren die ZIP " + tag(expected) + ".zip von " + RELEASES + tag(expected)
                + " in dieses Verzeichnis entpacken (oder ./gradlew :nlp-parser:downloadGermanParserModel); "
                + "die App lädt nichts aus dem Netz.";
    }

    static String tag(ExpectedModel model) {
        return model.modelId() + "-v" + model.modelVersion();
    }

    /** {@code quelle/seite@fassung} → Referenz mit genau diesem Schlüssel ({@link DocumentRef#key()}). */
    static DocumentRef ref(String documentId) {
        int slash = documentId.indexOf('/');
        int at = documentId.lastIndexOf('@');
        if (slash < 1 || at < slash) {
            return new DocumentRef(documentId, "-", null);
        }
        return new DocumentRef(documentId.substring(0, slash), documentId.substring(slash + 1, at),
                documentId.substring(at + 1));
    }

    static ParsedStatements map(ParsedDocument document, ObjectCentricAnalysis analysis) {
        List<ParsedStatements.Sentence> sentences = new ArrayList<>();
        for (Sentence sentence : document.sentences()) {
            sentences.add(new ParsedStatements.Sentence(sentence.index(), sentence.start(), sentence.end()));
        }
        List<ParsedStatements.Mention> mentions = new ArrayList<>();
        for (Mention mention : analysis.mentions()) {
            mentions.add(new ParsedStatements.Mention(mention.id(), mention.sentence(), mention.start(),
                    mention.end(), mention.text(), mention.headForm(), mention.lemma(),
                    ParsedStatements.MentionKind.valueOf(mention.kind().name()),
                    mention.resolution() == Resolution.UNRESOLVED, mention.cases()));
        }
        Set<String> core = new HashSet<>();
        for (CoreRelation relation : SalienceProjection.project(analysis).core()) {
            core.add(relation.statement());
        }
        List<ParsedStatements.Statement> statements = new ArrayList<>();
        for (Statement statement : analysis.statements()) {
            Sentence sentence = document.sentences().get(statement.sentence());
            int start = Integer.MAX_VALUE;
            int end = 0;
            for (int id : statement.predicate().tokens()) {
                Token token = sentence.token(id);
                start = Math.min(start, token.start());
                end = Math.max(end, token.end());
            }
            if (start > end) {
                start = sentence.start();
                end = sentence.start();
            }
            List<ParsedStatements.Argument> arguments = new ArrayList<>();
            for (Argument argument : statement.arguments()) {
                arguments.add(new ParsedStatements.Argument(
                        ParsedStatements.Role.valueOf(argument.role().name()), argument.mention(),
                        argument.relation(), argument.marker(),
                        ParsedStatements.Priority.valueOf(argument.priority().name()),
                        ParsedStatements.Basis.valueOf(argument.basis().name()), argument.certain()));
            }
            Set<String> findings = new HashSet<>();
            for (Finding finding : statement.findings()) {
                findings.add(finding.name());
            }
            statements.add(new ParsedStatements.Statement(statement.id(), statement.sentence(), start, end,
                    statement.predicate().text(), statement.predicate().lemma(),
                    ParsedStatements.Voice.valueOf(statement.voice().name()),
                    ParsedStatements.Clause.valueOf(statement.clause().name()), core.contains(statement.id()),
                    arguments, findings));
        }
        List<ParsedStatements.Attachment> attachments = new ArrayList<>();
        for (NominalAttachment attachment : analysis.attachments()) {
            attachments.add(new ParsedStatements.Attachment(attachment.id(), attachment.head(),
                    attachment.dependent(), attachment.relation(), attachment.marker(),
                    ParsedStatements.AttachmentKind.valueOf(attachment.kind().name()),
                    ParsedStatements.Basis.valueOf(attachment.basis().name())));
        }
        List<String> components = new ArrayList<>();
        for (Component component : document.components()) {
            components.add(component.layer() + "=" + component.tool()
                    + (component.model() == null ? "" : " " + component.model())
                    + (component.modelSha256() == null ? "" : " sha256:" + component.modelSha256()
                    .toLowerCase(Locale.ROOT)));
        }
        return new ParsedStatements(sentences, mentions, statements, attachments, components,
                ConllU.write(document));
    }
}
