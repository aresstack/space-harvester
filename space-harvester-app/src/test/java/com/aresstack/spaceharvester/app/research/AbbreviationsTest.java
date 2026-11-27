package com.aresstack.spaceharvester.app.research;

import com.aresstack.spaceharvester.app.runtime.GlossaryAccess;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        Map<String, List<GlossaryAccess.Entry>> glossary = Map.of(
                "OGR", List.of(entry("OGR", ReviewStatus.CONFIRMED, "Obstgehölzregister des Kreises")),
                "KMV", List.of(entry("Kompostmietenverordnung (KMV)", ReviewStatus.CONFIRMED,
                        "Regeln für Kompostmieten auf Gemeinschaftsflächen.")),
                "WZB", List.of(entry("WZB", ReviewStatus.PROPOSED, "Wurzelbereich")),
                "LKS", List.of(entry("LKS", ReviewStatus.CONFIRMED, " ")));
        GlossaryAccess access = (GlossaryAccess) java.lang.reflect.Proxy.newProxyInstance(
                GlossaryAccess.class.getClassLoader(), new Class<?>[] {GlossaryAccess.class}, (proxy, method, args) -> {
                    if (method.getName().equals("search")) {
                        GlossaryAccess.Search search = (GlossaryAccess.Search) args[0];
                        assertThat(search.status()).isEqualTo(ReviewStatus.CONFIRMED);
                        return new GlossaryAccess.Page(glossary.getOrDefault(search.q(), List.of()), null, true);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        Abbreviations.Result result = Abbreviations.resolve("OGR, KMV, WZB, LKS und XYZ",
                Abbreviations.glossary(access));

        assertThat(result.resolved()).extracting(Abbreviations.Resolved::line).containsExactly(
                "OGR = Obstgehölzregister des Kreises",
                "KMV = Kompostmietenverordnung – Regeln für Kompostmieten auf Gemeinschaftsflächen.");
        assertThat(result.unresolved()).containsExactly("WZB", "LKS", "XYZ");
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
