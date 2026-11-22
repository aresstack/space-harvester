package com.aresstack.spaceharvester.application.podcast;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Der Sprechtext für die Sprachausgabe (Angelo 22.11.2026: Bindestriche bringen die Stimme aus dem Tritt). */
class SpeakableTextTest {

    /** Geschützter Bindestrich, wie ihn das Modell schreibt. */
    private static final String NB = "‑";

    @Test
    void protectedAndWordHyphensBecomeSilent() {
        assertThat(SpeakableText.of("Das neue 3" + NB + "D" + NB + "Datenarchiv ist da."))
                .isEqualTo("Das neue 3 D Datenarchiv ist da.");
        assertThat(SpeakableText.of("Ab Januar starten wir den 1" + NB + "zu" + NB + "1" + NB + "Test."))
                .isEqualTo("Ab Januar starten wir den 1 zu 1 Test.");
        assertThat(SpeakableText.of("Der Roll-Out läuft.")).isEqualTo("Der Roll Out läuft.");
        assertThat(SpeakableText.of("Gelände" + NB + " und Gewässerdaten, Projekt- oder Prozessfragen"))
                .isEqualTo("Gelände und Gewässerdaten, Projekt oder Prozessfragen");
        assertThat(SpeakableText.of("Willkommen­seite")).isEqualTo("Willkommenseite");
    }

    @Test
    void datesRangesDashesAndArrowsAreSpokenAsWords() {
        assertThat(SpeakableText.of("Termin am 2026-11-20.")).isEqualTo("Termin am 20.11.2026.");
        assertThat(SpeakableText.of("Ansprechstellen aus den Bereichen 801–805.")).isEqualTo(
                "Ansprechstellen aus den Bereichen 801 bis 805.");
        assertThat(SpeakableText.of("Das Archiv – endlich da – hilft.")).isEqualTo("Das Archiv, endlich da, hilft.");
        assertThat(SpeakableText.of("Prüfung → Freigabe")).isEqualTo("Prüfung, dann Freigabe");
        assertThat(SpeakableText.of("Prüfung -> Freigabe")).isEqualTo("Prüfung, dann Freigabe");
    }

    @Test
    void pathsHexNumbersAndSymbolsBecomeSpeakable() {
        assertThat(SpeakableText.of("Die Regel liegt unter /etc/sudoers.d bereit."))
                .isEqualTo("Die Regel liegt unter etc sudoers.d bereit.");
        assertThat(SpeakableText.of("Der Fehlercode 0x2080 kommt selten.")).isEqualTo(
                "Der Fehlercode hexadezimal 2080 kommt selten.");
        assertThat(SpeakableText.of("Rund 8 100 Datensätze, 120 000 Messungen, Bereiche 801 802"))
                .isEqualTo("Rund 8100 Datensätze, 120000 Messungen, Bereiche 801 802");
        assertThat(SpeakableText.of("Zu 70 % fertig, Bau & Betrieb, JBoss/Liberty"))
                .isEqualTo("Zu 70 Prozent fertig, Bau und Betrieb, JBoss Liberty");
    }

    @Test
    void abbreviationsAreWrittenOutAndAcronymsSpelled() {
        assertThat(SpeakableText.of("Etwa z. B. Karten, d.h. ca. 5 Stück bzw. mehr usw."))
                .isEqualTo("Etwa zum Beispiel Karten, das heißt circa 5 Stück beziehungsweise mehr und so weiter.");
        assertThat(SpeakableText.of("Willkommen zum BSCW" + NB + "Update!")).isEqualTo(
                "Willkommen zum Be Es Ze We Update!");
    }

    @Test
    void subtractionIdentifiersUrlsAndSentenceEndsKeepTheirMeaning() {
        // Codex-Review #1161
        assertThat(SpeakableText.of("10 \u2212 5 ergibt 5.")).isEqualTo("10 minus 5 ergibt 5.");
        assertThat(SpeakableText.of("Die Lücke CVE-2026-1234 ist geschlossen.")).isEqualTo(
                "Die Lücke CVE 2026 1234 ist geschlossen.");
        assertThat(SpeakableText.of("Bereiche 801-805 helfen.")).isEqualTo("Bereiche 801 bis 805 helfen.");
        assertThat(SpeakableText.of("Mehr unter https://example.org/info steht da.")).isEqualTo(
                "Mehr unter steht da.");
        assertThat(SpeakableText.of("Wir brauchen Karten usw. Danach geht es weiter.")).isEqualTo(
                "Wir brauchen Karten und so weiter. Danach geht es weiter.");
    }

    @Test
    void plainTextStaysAsItIs() {
        String text = "Hey zusammen, willkommen! Diese Woche gibt es ein großes Highlight, das unsere Arbeit verändert.";
        assertThat(SpeakableText.of(text)).isEqualTo(text);
        assertThat(SpeakableText.of(null)).isEmpty();
    }
}
