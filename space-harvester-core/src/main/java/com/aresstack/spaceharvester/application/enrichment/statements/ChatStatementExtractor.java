package com.aresstack.spaceharvester.application.enrichment.statements;

import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementCandidates.Context;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementCandidates.EventCandidate;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementCandidates.MentionCandidate;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementCandidates.OrderCandidate;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementCandidates.ParticipantCandidate;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementCandidates.RelationCandidate;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementCandidates.Words;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link StatementExtractor} über das Chat-Modell (#108, Prompt und Antwortformat unverändert aus dem Enterprise AI
 * Client): eine Anfrage je Absatz mit nummerierten Wörtern je Satz, eine strenge JSON-Antwort mit Wortbereichen, eine
 * Reparaturanfrage, wenn die Antwort unlesbar ist. Der Absatz geht nur an das eingestellte Chat-Modell; ohne eines ist
 * der Extraktor nicht verfügbar.
 */
public final class ChatStatementExtractor implements StatementExtractor {

    /** Version von Anweisung und Antwortformat; Teil von {@link #version()}. */
    static final String PROMPT_VERSION = "1";

    static final String INSTRUCTION = "Du zerlegst Sätze eines Dokuments in überprüfbare Aussagen. Du erfindest "
            + "nichts: Jede Angabe muss wörtlich im angegebenen Satz stehen; kein Weltwissen, keine Ergänzungen, keine "
            + "Schlussfolgerungen. Im Zweifel lässt du eine Angabe weg.\n"
            + "Jeder Satz steht in einer Zeile \"Satz n:\", jedes Wort trägt davor seine Nummer [k]. Wortangaben haben "
            + "die Form {\"from\": k, \"to\": m, \"text\": \"...\"}: erste und letzte Wortnummer im selben Satz und der "
            + "Wortlaut genau so, wie er dort steht.\n"
            + "Antworte ausschließlich mit einem JSON-Objekt dieser Form:\n"
            + "{\"mentions\": [{\"id\": \"m1\", \"sentence\": 1, \"from\": 1, \"to\": 2, \"text\": \"\", "
            + "\"lemma\": \"\", \"pronoun\": false}], "
            + "\"events\": [{\"id\": \"e1\", \"sentence\": 1, \"predicate\": {\"from\": 3, \"to\": 3, \"text\": \"\"}, "
            + "\"particle\": null, \"lemma\": \"\", \"participants\": [{\"role\": \"ACTOR\", \"mention\": \"m1\"}], "
            + "\"negation\": null, \"modality\": null, \"condition\": null}], "
            + "\"relations\": [{\"id\": \"r1\", \"sentence\": 1, \"kind\": \"STATE\", \"subject\": \"m2\", "
            + "\"value\": {\"from\": 5, \"to\": 5, \"text\": \"\"}, \"lemma\": \"\", \"negation\": null, "
            + "\"modality\": null, \"condition\": null}], "
            + "\"order\": [{\"before\": \"e1\", \"after\": \"e2\", \"sentence\": 1, "
            + "\"cue\": {\"from\": 9, \"to\": 9, \"text\": \"\"}}]}\n"
            + "- mentions: jede Nominalgruppe, so wie sie im Satz steht, mit Artikel und Adjektiven (\"den "
            + "eingereichten Beleg\"). Ein fester Mehrwortbegriff (\"elektronische Zahlungsanordnung\") ist eine "
            + "einzige Erwähnung. lemma: Grundform des Begriffs im Nominativ Singular ohne Artikel; beschreibende "
            + "Adjektive und Partizipien (zuständig, eingereicht) gehören nicht hinein, sondern in eine Relation; ein "
            + "fester Fachbegriff bleibt vollständig. Pronomen und Relativpronomen (er, sie, es, dieser, der, die, "
            + "das) sind Erwähnungen mit \"pronoun\": true und ohne lemma; löse sie nie auf.\n"
            + "- events: eine je Handlung. predicate: das Vollverb, im Passiv das Partizip (nicht \"wird\"); "
            + "particle: die abgetrennte Verbpartikel (\"aus\" in \"löst ... aus\") oder null; lemma: Infinitiv mit "
            + "Partikel (\"auslösen\"). participants nur aus demselben Satz, role ACTOR (wer handelt; im Passiv die "
            + "Gruppe mit \"durch\" oder \"von\"), AFFECTED_OBJECT (worauf sich die Handlung richtet), RECIPIENT (wer "
            + "etwas erhält), INSTRUMENT (womit), LOCATION (wo). Das grammatische Subjekt ist nicht immer der Akteur. "
            + "Teilen sich koordinierte Verben ein Subjekt (\"prüft ... und löst ... aus\"), ist es bei beiden "
            + "Handlungen dasselbe; das Objekt der einen Handlung wird nie Akteur der anderen. Was der Satz nicht "
            + "eindeutig sagt, lässt du weg.\n"
            + "- relations: kind ATTRIBUTE (dauerhafte Eigenschaft) oder STATE (Zustand, oft ein Partizip); subject "
            + "ist die Erwähnung, value die Wörter der Eigenschaft im selben Satz, lemma ihre Grundform.\n"
            + "- negation, modality, condition: Wörter desselben Satzes (Verneinung wie \"nicht\", Modalverb wie "
            + "\"kann\" oder \"muss\", Bedingung wie \"wenn der Betrag die Grenze übersteigt\") oder null. Eine "
            + "verneinte, modale oder bedingte Aussage hat immer diese Angabe.\n"
            + "- order: nur bei einem ausdrücklichen Signalwort im Satz (anschließend, danach, zuvor, nachdem, "
            + "bevor); before ist die frühere Handlung.\n"
            + "Beispiel: Satz 1: [1]Der [2]Antragsteller [3]reicht [4]das [5]ausgefüllte [6]Formular [7]ein [8]und "
            + "[9]danach [10]prüft [11]die [12]Fachabteilung [13]den [14]Antrag. Erwähnungen m1 (1-2, "
            + "Antragsteller), m2 (4-6, Formular), m3 (11-12, Fachabteilung), m4 (13-14, Antrag); e1 einreichen "
            + "(predicate 3, particle 7, ACTOR m1, AFFECTED_OBJECT m2); e2 prüfen (predicate 10, ACTOR m3, "
            + "AFFECTED_OBJECT m4); r1 STATE m2 \"ausgefüllte\" (ausgefüllt); order e1 vor e2, cue 9.";

    static final String REPAIR = "Deine Antwort war kein lesbares JSON-Objekt. Antworte jetzt ausschließlich mit dem "
            + "JSON-Objekt in der beschriebenen Form, ohne weiteren Text.";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Supplier<StatementChat> chat;

    /** @param chat das eingestellte Chat-Modell oder {@code null}: nicht verfügbar */
    public ChatStatementExtractor(Supplier<StatementChat> chat) {
        this.chat = Objects.requireNonNull(chat, "chat");
    }

    /** Die Modellkennung, wenn ein Modell mit Kennung eingestellt ist, sonst {@code null}. */
    static String model(StatementChat chat) {
        String model = chat == null ? null : chat.model();
        return model == null || model.isBlank() ? null : model.strip();
    }

    @Override
    public boolean isAvailable() {
        return model(chat.get()) != null;
    }

    @Override
    public String version() {
        StatementChat current = chat.get();
        String selected = model(current);
        if (selected == null) {
            return "chat-json/" + PROMPT_VERSION + "/";
        }
        String endpoint = current.endpoint();
        return "chat-json/" + PROMPT_VERSION + "/" + selected
                + (endpoint == null || endpoint.isBlank() ? "" : "@" + endpoint.strip());
    }

    @Override
    public StatementCandidates extract(ParagraphText paragraph) {
        StatementChat model = chat.get();
        if (model(model) == null) {
            throw new StatementExtractionException("no chat model selected");
        }
        List<StatementChat.Message> messages = new ArrayList<>(List.of(StatementChat.Message.system(INSTRUCTION),
                StatementChat.Message.user(paragraph.numbered())));
        String answer = ask(model, messages);
        try {
            return parse(answer);
        } catch (StatementExtractionException unreadable) {
            messages.add(StatementChat.Message.assistant(answer == null ? "" : answer));
            messages.add(StatementChat.Message.user(REPAIR));
            try {
                return parse(ask(model, messages));
            } catch (StatementExtractionException again) {
                throw new StatementExtractionException(unreadable.getMessage() + "; after repair: "
                        + again.getMessage());
            }
        }
    }

    private static String ask(StatementChat model, List<StatementChat.Message> messages) {
        try {
            return model.complete(List.copyOf(messages));
        } catch (AiServiceException e) {
            throw new StatementExtractionException("request failed: " + e.reason(), e);
        } catch (RuntimeException e) {
            throw new StatementExtractionException("request failed: " + e.getClass().getSimpleName(), e);
        }
    }

    /** Liest die Antwort; eine unlesbare scheitert mit ihrer Form, nie mit ihrem Text. */
    static StatementCandidates parse(String answer) {
        if (answer == null || answer.trim().isEmpty()) {
            throw new StatementExtractionException("empty answer");
        }
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new StatementExtractionException("no JSON object in " + answer.length() + " characters");
        }
        JsonNode root;
        try {
            root = JSON.readTree(answer.substring(start, end + 1));
        } catch (JacksonException e) {
            // Nur die Art: die Meldung von Jackson zitiert die Antwort.
            throw new StatementExtractionException("unreadable JSON: " + e.getClass().getSimpleName());
        }
        if (root == null || !root.isObject()) {
            throw new StatementExtractionException("unreadable JSON: JSON value is no object");
        }
        if (!root.has("mentions") && !root.has("events") && !root.has("relations")) {
            throw new StatementExtractionException("JSON without mentions, events or relations");
        }
        int malformed = 0;
        List<MentionCandidate> mentions = new ArrayList<>();
        for (JsonNode entry : array(root, "mentions")) {
            JsonNode object = object(entry);
            Integer sentence = integer(object, "sentence");
            String key = string(object, "id");
            if (object == null || key == null || sentence == null) {
                malformed++;
                continue;
            }
            mentions.add(new MentionCandidate(key, sentence, new Words(number(object, "from"), number(object, "to"),
                    string(object, "text")), string(object, "lemma"), bool(object, "pronoun")));
        }
        List<EventCandidate> events = new ArrayList<>();
        for (JsonNode entry : array(root, "events")) {
            JsonNode object = object(entry);
            Integer sentence = integer(object, "sentence");
            String key = string(object, "id");
            Words predicate = words(member(object, "predicate"));
            if (object == null || key == null || sentence == null || predicate == null) {
                malformed++;
                continue;
            }
            List<ParticipantCandidate> participants = new ArrayList<>();
            for (JsonNode participant : array(object, "participants")) {
                JsonNode fields = object(participant);
                if (fields == null || string(fields, "role") == null || string(fields, "mention") == null) {
                    malformed++;
                    continue;
                }
                participants.add(new ParticipantCandidate(string(fields, "role"), string(fields, "mention")));
            }
            events.add(new EventCandidate(key, sentence, predicate, words(member(object, "particle")),
                    string(object, "lemma"), participants, context(object)));
        }
        List<RelationCandidate> relations = new ArrayList<>();
        for (JsonNode entry : array(root, "relations")) {
            JsonNode object = object(entry);
            Integer sentence = integer(object, "sentence");
            String key = string(object, "id");
            Words value = words(member(object, "value"));
            if (object == null || key == null || sentence == null || value == null) {
                malformed++;
                continue;
            }
            relations.add(new RelationCandidate(key, sentence, string(object, "kind"), string(object, "subject"),
                    value, string(object, "lemma"), context(object)));
        }
        List<OrderCandidate> orders = new ArrayList<>();
        for (JsonNode entry : array(root, "order")) {
            JsonNode object = object(entry);
            Integer sentence = integer(object, "sentence");
            Words cue = words(member(object, "cue"));
            if (object == null || sentence == null || cue == null || string(object, "before") == null
                    || string(object, "after") == null) {
                malformed++;
                continue;
            }
            orders.add(new OrderCandidate(string(object, "before"), string(object, "after"), sentence, cue));
        }
        return new StatementCandidates(mentions, events, relations, orders, malformed);
    }

    private static Context context(JsonNode object) {
        return new Context(words(member(object, "negation")), words(member(object, "modality")),
                words(member(object, "condition")));
    }

    private static Words words(JsonNode object) {
        if (object == null) {
            return null;
        }
        String text = string(object, "text");
        int first = number(object, "from");
        if ((text == null || text.trim().isEmpty()) && first < 1) {
            return null;
        }
        return new Words(first, number(object, "to") < first ? first : number(object, "to"), text);
    }

    private static int number(JsonNode object, String name) {
        Integer value = integer(object, name);
        return value == null ? 0 : value;
    }

    // Typisierter Zugriff wie Json im Client: falscher Typ zählt wie fehlend.

    private static JsonNode object(JsonNode value) {
        return value != null && value.isObject() ? value : null;
    }

    private static JsonNode member(JsonNode object, String name) {
        return object == null ? null : object(object.get(name));
    }

    private static List<JsonNode> array(JsonNode object, String name) {
        JsonNode value = object == null ? null : object.get(name);
        List<JsonNode> entries = new ArrayList<>();
        if (value != null && value.isArray()) {
            value.forEach(entries::add);
        }
        return entries;
    }

    private static String string(JsonNode object, String name) {
        JsonNode value = object == null ? null : object.get(name);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static Integer integer(JsonNode object, String name) {
        JsonNode value = object == null ? null : object.get(name);
        if (value == null || !value.isNumber()) {
            return null;
        }
        try {
            return value.decimalValue().intValueExact(); // 3 und 3.0 wie im Client, 3.5 nicht
        } catch (ArithmeticException notIntegral) {
            return null;
        }
    }

    private static boolean bool(JsonNode object, String name) {
        JsonNode value = object == null ? null : object.get(name);
        return value != null && value.isBoolean() && value.booleanValue();
    }
}
