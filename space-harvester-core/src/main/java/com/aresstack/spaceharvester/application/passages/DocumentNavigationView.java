package com.aresstack.spaceharvester.application.passages;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Objects;

/**
 * Die Dokumentnavigation einer Seite für den Reader (Seitenleiste „Abschnitte“) und die REST-Schnittstelle (#181,
 * ADR 0020), gelesen aus dem gespeicherten Ergebnis von {@value NavigationProcessor#ID} für genau den angezeigten Text,
 * mit demselben Zustandsbegriff wie {@link PagePassages}. Fehlt ein brauchbares Ergebnis, zeigt sie wie
 * {@code outline} im Client sofort die Überschriften der Seite (FA-01).
 *
 * @param state    ob und warum ein Ergebnis vorliegt
 * @param analysis ob Themenabschnitte erkannt sind
 * @param reason   bei {@link DocumentNavigationBuilder.Analysis#HEADINGS} warum ({@code no-model},
 *                 {@code source-not-approved}, {@code not-embedded}, {@code no-topic-change},
 *                 {@code model-unavailable}), sonst {@code null}; ohne gespeichertes Ergebnis {@code null}
 * @param chapters Hauptkapitel in Lesereihenfolge
 */
public record DocumentNavigationView(PagePassages.State state, DocumentNavigationBuilder.Analysis analysis,
                                     String reason, List<NavigationSection> chapters) {

    public DocumentNavigationView {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(analysis, "analysis");
        chapters = List.copyOf(chapters);
    }

    /**
     * Liest die Navigation einer Seite.
     *
     * @param key   die angezeigte Seite; Ergebnisse anderer Seiten werden nie verwendet
     * @param views die Anreicherungen dieser Seite ({@link Enrichments#view})
     * @param text  der angezeigte Text aus derselben Payload oder {@code null}
     */
    public static DocumentNavigationView read(PageKey key, List<Enrichments.View> views, PageText text,
                                              DocumentNavigationBuilder builder) {
        Objects.requireNonNull(key, "key");
        Enrichments.View view = views.stream()
                .filter(candidate -> NavigationProcessor.ID.equals(candidate.enrichment().processorId())
                        && key.equals(candidate.enrichment().key()))
                .findFirst().orElse(null);
        if (text == null) {
            return new DocumentNavigationView(PagePassages.State.MISSING,
                    DocumentNavigationBuilder.Analysis.HEADINGS, null, List.of());
        }
        if (view == null) {
            return headings(PagePassages.State.MISSING, text, builder);
        }
        Enrichment result = view.enrichment();
        if (result.status() == Enrichment.Status.FAILED) {
            return headings(PagePassages.State.FAILED, text, builder);
        }
        if (!result.inputSha256().equals(text.inputSha256())) {
            return headings(PagePassages.State.STALE, text, builder);
        }
        DocumentNavigationBuilder.Navigation navigation;
        try {
            navigation = NavigationPayload.parse(result.payloadJson(), text.text());
        } catch (IllegalArgumentException e) {
            return headings(PagePassages.State.FAILED, text, builder);
        }
        return new DocumentNavigationView(view.freshness() == Enrichment.Freshness.CURRENT
                ? PagePassages.State.CURRENT : PagePassages.State.STALE, navigation.analysis(), navigation.reason(),
                navigation.chapters());
    }

    private static DocumentNavigationView headings(PagePassages.State state, PageText text,
                                                   DocumentNavigationBuilder builder) {
        return new DocumentNavigationView(state, DocumentNavigationBuilder.Analysis.HEADINGS, null,
                builder.headingsOnly(text, null).chapters());
    }
}
