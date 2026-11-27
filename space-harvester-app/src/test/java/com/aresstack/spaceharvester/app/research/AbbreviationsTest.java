package com.aresstack.spaceharvester.app.research;

import com.aresstack.spaceharvester.app.runtime.GlossaryAccess;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Erfundene Stichpunkte und Glossareinträge. */
class AbbreviationsTest {

    @Test
    void akronymeEinmalInReihenfolgeOhneSatzzeichen() {
        assertThat(Abbreviations.acronyms("Wie hängen OGR und das KMV zusammen? Ablauf im KMV, siehe auch (WZB). "
                + "Dazu A, Ok, ABC-Liste, VV-ZBR, LPF-Programm und Version 2."))
                .containsExactly("OGR", "KMV", "WZB", "ABC", "VV-ZBR", "LPF");
    }

    @Test
    void nurBestaetigteEintraegeMitErlaeuterungLoesenAuf() {
        // Die echte Begriffssuche fände „Kompostmietenverordnung (KMV)“ unter „KMV“ nicht; deshalb ohne Suchwort.
        List<GlossaryAccess.Entry> glossary = List.of(
                entry("Kompostmietenverordnung (KMV)", ReviewStatus.CONFIRMED,
                        "Regeln für Kompostmieten auf Gemeinschaftsflächen."),
                entry("OGR", ReviewStatus.CONFIRMED, "Obstgehölzregister des Kreises"),
                entry("WZB", ReviewStatus.PROPOSED, "Wurzelbereich"),
                entry("LKS", ReviewStatus.CONFIRMED, " "));
        int[] calls = {0};
        GlossaryAccess access = (GlossaryAccess) java.lang.reflect.Proxy.newProxyInstance(
                GlossaryAccess.class.getClassLoader(), new Class<?>[] {GlossaryAccess.class}, (proxy, method, args) -> {
                    if (method.getName().equals("search")) {
                        GlossaryAccess.Search search = (GlossaryAccess.Search) args[0];
                        assertThat(search.status()).isEqualTo(ReviewStatus.CONFIRMED);
                        assertThat(search.q()).isNull();
                        calls[0]++;
                        return new GlossaryAccess.Page(glossary.stream()
                                .filter(e -> e.status() == search.status()).toList(), null, true);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        Abbreviations.Result result = Abbreviations.resolve("OGR, KMV, WZB, LKS und XYZ",
                Abbreviations.glossary(access));

        assertThat(result.resolved()).extracting(Abbreviations.Resolved::line).containsExactly(
                "OGR = Obstgehölzregister des Kreises",
                "KMV = Kompostmietenverordnung – Regeln für Kompostmieten auf Gemeinschaftsflächen.");
        assertThat(result.unresolved()).containsExactly("WZB", "LKS", "XYZ");
        assertThat(calls[0]).as("das Glossar wird einmal gelesen, nicht je Abkürzung").isEqualTo(1);
    }

    @Test
    void einFehlerDesGlossarsHeisstNichtAufgeloest() {
        GlossaryAccess broken = (GlossaryAccess) java.lang.reflect.Proxy.newProxyInstance(
                GlossaryAccess.class.getClassLoader(), new Class<?>[] {GlossaryAccess.class}, (proxy, method, args) -> {
                    throw new IllegalStateException("weg");
                });
        assertThat(Abbreviations.glossary(broken).apply("OGR")).isEqualTo(Optional.empty());
    }

    private static GlossaryAccess.Entry entry(String term, ReviewStatus status, String explanation) {
        return new GlossaryAccess.Entry("id-" + term, term, term.toLowerCase(), null, null, RelationOrigin.MANUAL,
                status, List.of(), false, explanation, null, Instant.EPOCH, Instant.EPOCH, null, 1);
    }
}
