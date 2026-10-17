package com.aresstack.spaceharvester.application.enrichment.language;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.JsonText;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Handlungen einer Seite ({@link ActivityFinder}): je Teilsatz das Prädikat im Wortlaut, eine vorgeschlagene
 * Grundform, Aktiv oder Passiv, Aussage oder Anweisung, Modalverb, Verneinung und die Beteiligten mit semantischer
 * Rolle ({@code ACTOR}, {@code AFFECTED_OBJECT}, {@code RECIPIENT}, {@code INSTRUMENT}). Pronomen erscheinen als
 * Beteiligte mit {@code "pronoun":true}; ihr Bezug wird nicht aufgelöst.
 *
 * <p>Ergebnis {@code {"activities":[{"predicate","lemma","voice","mood","modal","negated","participants":[{"role",
 * "term","normalized","pronoun"}],"section","charStart","charEnd"}]}} in Textreihenfolge, höchstens
 * {@value #MAX_ACTIVITIES} Einträge.
 */
public class ActivityProcessor implements EnrichmentProcessor {

    static final int MAX_ACTIVITIES = 1000;

    @Override
    public String id() {
        return "activities";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public EnrichmentOutput process(PageText text) {
        GermanText analysis = GermanText.analyze(text);
        List<Activity> found = new ArrayList<>();
        for (Sentence sentence : analysis.sentences()) {
            found.addAll(ActivityFinder.find(sentence));
        }
        List<String> warnings = new ArrayList<>(analysis.warnings());
        if (found.size() > MAX_ACTIVITIES) {
            warnings.add("Nur die ersten " + MAX_ACTIVITIES + " von " + found.size() + " Handlungen");
        }
        StringBuilder json = new StringBuilder("{\"activities\":[");
        for (int i = 0; i < Math.min(found.size(), MAX_ACTIVITIES); i++) {
            Activity activity = found.get(i);
            String section = analysis.sectionAt(activity.charStart());
            json.append(i == 0 ? "" : ",")
                    .append("{\"predicate\":").append(JsonText.quote(activity.predicateText()))
                    .append(",\"lemma\":").append(JsonText.quote(activity.lemma()))
                    .append(",\"voice\":").append(activity.passive() ? "\"passive\"" : "\"active\"")
                    .append(",\"mood\":").append(activity.instruction() ? "\"instruction\"" : "\"statement\"")
                    .append(",\"modal\":").append(activity.modal() == null ? "null"
                            : JsonText.quote(activity.modal().toLowerCase(Locale.ROOT)))
                    .append(",\"negated\":").append(activity.negated())
                    .append(",\"participants\":[");
            for (int p = 0; p < activity.participants().size(); p++) {
                Activity.Participant participant = activity.participants().get(p);
                json.append(p == 0 ? "" : ",")
                        .append("{\"role\":").append(JsonText.quote(participant.role().name()))
                        .append(",\"term\":").append(JsonText.quote(participant.term()))
                        .append(",\"normalized\":").append(JsonText.quote(participant.normalized()))
                        .append(",\"pronoun\":").append(participant.group() == null).append('}');
            }
            json.append("],\"section\":").append(section == null ? "null" : JsonText.quote(section))
                    .append(",\"charStart\":").append(activity.charStart())
                    .append(",\"charEnd\":").append(activity.charEnd()).append('}');
        }
        return new EnrichmentOutput(json.append("]}").toString(), warnings);
    }
}
