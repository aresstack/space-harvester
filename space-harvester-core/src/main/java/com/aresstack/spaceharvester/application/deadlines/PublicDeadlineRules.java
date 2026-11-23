package com.aresstack.spaceharvester.application.deadlines;

import com.aresstack.spaceharvester.application.deadlines.DeadlineRule.Formula;
import com.aresstack.spaceharvester.application.deadlines.DeadlineRule.Kind;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.aresstack.spaceharvester.application.deadlines.DeadlineFieldKeys.BOOK_ADVANCE;
import static com.aresstack.spaceharvester.application.deadlines.DeadlineFieldKeys.BOOK_CUSTODY;
import static com.aresstack.spaceharvester.application.deadlines.DeadlineFieldKeys.CATEGORY_PASS_THROUGH;

/**
 * Die öffentlichen Fristenregeln mit belegtem Wortlaut (ADR 0035, Abschnitt 7; geprüft am 23.11.2026): § 60 Abs. 1 BHO
 * und Nr. 6, Nr. 8, Nr. 9.2.2.3, Nr. 10 und Anlage 2 der Vorschuss- und Verwahrungsrichtlinie des Bundes (VO/VW-RiB,
 * Stand 04/21, GMBl 2021 Nr. 57, S. 1246) sowie Nr. 5.5 Bewi-Bund (Stand 10/2026, gültig ab 01.01.2027). Wortlaut und
 * Seitenzahlen aus den amtlichen PDF-Fassungen der ZRB-Seite. Grundsatz und Ausnahmen getrennt; jede Ausnahme nennt die
 * verdrängte Regel. Befristete Ausnahmen (Abschnitte 7 und 8 bis Ablauf 2024) wirken danach nur mit neuem Nachweis.
 */
public final class PublicDeadlineRules implements DeadlineRuleCatalog {

    /** Version des Katalogs. */
    public static final String VERSION = "public-2";

    static final String BHO = "https://www.gesetze-im-internet.de/bho/__60.html (abgerufen 23.11.2026)";
    static final String RIB = "https://www.verwaltungsvorschriften-im-internet.de/"
            + "bsvwvbund_01092021_IIE4H10052010006003.htm (abgerufen 23.11.2026)";
    static final String ZRB = "https://zrb.bund.de/vorschriften/verwaltungsvorschriften-zur-bho";
    static final String RIB_PDF = "https://zrb.bund.de/fileadmin/downloads/vorschriften/vv-bho/vo-vw-rl/"
            + "VV_Parag_60_BHO_04-2021_bf.pdf (abgerufen 23.11.2026)";
    static final String BEWI_PDF = "https://zrb.bund.de/fileadmin/downloads/vorschriften/vv-bho/rds_buchf_zv/"
            + "Anlage_5_Bewi-Bund_202610.pdf (abgerufen 23.11.2026)";
    static final String BEWI_FROM_SOURCE = ZRB + " („Anlage 5: … (Bewi-Bund) (gültig ab 1. Januar 2027)“, "
            + "abgerufen 23.11.2026)";
    /** Bewi-Bund gilt laut ZRB-Seite ab 01.01.2027. */
    static final Optional<LocalDate> BEWI_FROM = Optional.of(LocalDate.of(2027, 1, 1));
    /** Nr. 10 VO/VW-RiB: Fristausnahmen der Abschnitte 7 und 8 „bis zum Ablauf des Jahres 2024“. */
    static final Optional<LocalDate> SECTIONS_7_8_UNTIL = Optional.of(LocalDate.of(2024, 12, 31));
    /** Fassung vom 01.09.2021 (Datum in der amtlichen Adresse, „Neufassung“). */
    static final Optional<LocalDate> RIB_FROM = Optional.of(LocalDate.of(2021, 9, 1));
    /** Für § 60 BHO ist kein Datum der Fassung belegt; die Wirksamkeit bleibt offen. */
    static final Optional<LocalDate> BHO_FROM = Optional.empty();

    static final String NR6 = "Ein Vorschuss ist so schnell wie möglich, grundsätzlich im Haushaltsjahr seiner "
            + "Entstehung auszugleichen, spätestens aber drei Monate nach Ende dieses Haushaltsjahres. Von diesen "
            + "Fristen ausgenommen sind die Auszahlungen für die Sockelbeträge einer Geldstelle und durchlaufende "
            + "Gelder. Durchlaufende Gelder, die als Vorschuss gebucht werden, sind spätestens zum Ende des zweiten auf "
            + "ihre Entstehung folgenden Haushaltsjahres auszugleichen.";
    static final String NR8 = "Eine Verwahrung ist so schnell wie möglich, grundsätzlich im Haushaltsjahr ihrer "
            + "Entstehung auszugleichen, spätestens aber drei Monate nach Ende dieses Haushaltsjahres. Von den Fristen "
            + "ausgenommen sind in Verwahrung gebuchte Sicherheiten, sowie beschlagnahmte Gelder in Straf- und "
            + "Bußgeldverfahren. Unanbringliche Auszahlungen sind spätestens drei Monate nach Ende des Monats, in dem "
            + "die unanbringliche Auszahlung als Einzahlung gebucht wurde, vom Bewirtschafter auszugleichen. "
            + "Durchlaufende Gelder, die als Verwahrung gebucht werden, sind so schnell wie möglich, grundsätzlich "
            + "spätestens zum Ende des zweiten auf ihre Entstehung folgenden Haushaltsjahres auszugleichen. Das "
            + "Bundesministerium der Finanzen kann Ausnahmen zulassen.";
    static final String NR9223 = "Einzahlungen, die bei der Kasse in Verwahrung gebucht wurden und deren "
            + "Verwendungszweck bis spätestens zum Ablauf des zweiten auf die Buchung folgenden Haushaltsjahres nicht "
            + "aufgeklärt oder der ursprüngliche Einzahler nicht ermittelt werden konnte, werden von der oder dem für die "
            + "Kasse zuständigen Beauftragten für den Haushalt bei Kap. 0813 Tit. 119 99 vereinnahmt.";
    static final String NR9223_SMALL = "Bei Beträgen bis 1.000 EURO gilt die Unanbringlichkeit als gegeben, wenn "
            + "innerhalb von sechs Monaten der Verwendungszweck der Einzahlung nicht aufgeklärt werden konnte.";
    static final String ANL2_ABOLISHED = "Die Frist für den Ausgleich der Konten ist aufgehoben.";
    static final String NR10_LIMIT = "Bestehende Ausnahmen zu den Fristen einzelner Konten für durchlaufende Gelder "
            + "(Buchungsstelle 9070 0000 97 und 9071 0000 96) behalten bis zum Ablauf des Jahres 2024 ihre Gültigkeit. "
            + "Im Bedarfsfall können für diese Konten neue Ausnahmeanträge gestellt werden.";
    static final String BEWI_5_5_3 = "Die Buchung einer haushaltsmäßigen Verrechnung zwischen dem Vorschuss bzw. dem "
            + "Verwahrungsbuch und dem Bundeshaushalt ist im gleichen Haushaltsjahr anzuordnen.";
    static final String BHO_60_1 = "Ein Vorschuß ist bis zum Ende des zweiten auf seine Entstehung folgenden "
            + "Haushaltsjahres endgültig zu buchen; Ausnahmen bedürfen der Einwilligung des Bundesministeriums der "
            + "Finanzen.";

    /** Kategorie „Sockelbetrag einer Geldstelle“. */
    public static final String CATEGORY_BASE_AMOUNT = "sockelbetrag-geldstelle";
    /** Kategorie „Sicherheit“. */
    public static final String CATEGORY_SECURITY = "sicherheit";
    /** Kategorie „beschlagnahmte Gelder in Straf- und Bußgeldverfahren“. */
    public static final String CATEGORY_SEIZED = "beschlagnahmte-gelder";
    /** Kategorie „unanbringliche Auszahlung“. */
    public static final String CATEGORY_UNDELIVERABLE = "unanbringliche-auszahlung";
    /** Kategorie „ungeklärte Einzahlung“ bei der Kasse (Nr. 9.2.2.3). */
    public static final String CATEGORY_UNCLEARED = "ungeklärte-einzahlung";
    /** Kategorie „unanbringliche Einzahlung“ (Nr. 9.2.2.3). */
    public static final String CATEGORY_UNDELIVERABLE_PAYMENT = "unanbringliche-einzahlung";

    private static final List<DeadlineRule> RULES = List.of(
            standard("rib-6-vorschuss", "Nr. 6 Satz 1 VO/VW-RiB", NR6, BOOK_ADVANCE),
            exception("rib-6-sockel", "Nr. 6 Satz 2 VO/VW-RiB", NR6, BOOK_ADVANCE, "rib-6-vorschuss",
                    CATEGORY_BASE_AMOUNT, false, Formula.EXEMPT),
            exception("rib-6-durchlaufend", "Nr. 6 Satz 2 und 3 VO/VW-RiB", NR6, BOOK_ADVANCE, "rib-6-vorschuss",
                    CATEGORY_PASS_THROUGH, false, Formula.END_OF_SECOND_FOLLOWING_YEAR),
            new DeadlineRule("bho-60-1-einwilligung", "1", "§ 60 Abs. 1 Satz 2 BHO", BHO_60_1, List.of(BHO),
                    BHO_FROM, BOOK_ADVANCE, Kind.EXCEPTION, Optional.of("rib-6-vorschuss"), Set.of(), true,
                    Formula.AS_GRANTED, Optional.empty(), Set.of(), Set.of(), "§ 60 Abs. 1 Satz 2 BHO"),
            standard("rib-8-verwahrung", "Nr. 8 Satz 1 VO/VW-RiB", NR8, BOOK_CUSTODY),
            exception("rib-8-sicherheit", "Nr. 8 Satz 2 VO/VW-RiB", NR8, BOOK_CUSTODY, "rib-8-verwahrung",
                    CATEGORY_SECURITY, false, Formula.EXEMPT),
            exception("rib-8-beschlagnahme", "Nr. 8 Satz 2 VO/VW-RiB", NR8, BOOK_CUSTODY, "rib-8-verwahrung",
                    CATEGORY_SEIZED, false, Formula.EXEMPT),
            exception("rib-8-unanbringlich", "Nr. 8 Satz 3 VO/VW-RiB", NR8, BOOK_CUSTODY, "rib-8-verwahrung",
                    CATEGORY_UNDELIVERABLE, false, Formula.THREE_MONTHS_AFTER_BOOKING_MONTH),
            exception("rib-8-durchlaufend", "Nr. 8 Satz 4 VO/VW-RiB", NR8, BOOK_CUSTODY, "rib-8-verwahrung",
                    CATEGORY_PASS_THROUGH, false, Formula.END_OF_SECOND_FOLLOWING_YEAR),
            exception("rib-8-bmf", "Nr. 8 Satz 5 VO/VW-RiB", NR8, BOOK_CUSTODY, "rib-8-verwahrung", null, true,
                    Formula.AS_GRANTED),
            new DeadlineRule("rib-9223-ungeklaert", "1", "Nr. 9.2.2.3 Satz 1 VO/VW-RiB", NR9223, List.of(RIB_PDF, RIB),
                    RIB_FROM, BOOK_CUSTODY, Kind.EXCEPTION, Optional.of("rib-8-verwahrung"),
                    Set.of(CATEGORY_UNCLEARED, CATEGORY_UNDELIVERABLE_PAYMENT), false,
                    Formula.END_OF_SECOND_YEAR_AFTER_BOOKING, Optional.empty(), Set.of(), Set.of(),
                    "VO/VW-RiB Nr. 9.2.2.3, S. 10"),
            new DeadlineRule("rib-9223-kleinbetrag", "1", "Nr. 9.2.2.3 Satz 6 VO/VW-RiB", NR9223_SMALL,
                    List.of(RIB_PDF, RIB), RIB_FROM, BOOK_CUSTODY, Kind.PROCEDURE, Optional.empty(),
                    Set.of(CATEGORY_UNCLEARED, CATEGORY_UNDELIVERABLE_PAYMENT), false, Formula.NONE,
                    Optional.empty(), Set.of(), Set.of(), "VO/VW-RiB Nr. 9.2.2.3, S. 10"),
            section("rib-anl2-abschnitt-7", "7", "9070000097"),
            section("rib-anl2-abschnitt-8", "8", "9071000096"),
            bewi("bewi-5-5-vorschuss", BOOK_ADVANCE),
            bewi("bewi-5-5-verwahrung", BOOK_CUSTODY));

    private static DeadlineRule standard(String id, String basis, String wording, String book) {
        return new DeadlineRule(id, "1", basis, wording, List.of(RIB, ZRB, RIB_PDF), RIB_FROM, book, Kind.STANDARD,
                Optional.empty(), Set.of(), false, Formula.THREE_MONTHS_AFTER_ORIGIN_YEAR, Optional.empty(), Set.of(),
                Set.of(), locator(wording));
    }

    private static DeadlineRule exception(String id, String basis, String wording, String book, String displaces,
                                          String category, boolean individual, Formula formula) {
        return new DeadlineRule(id, "1", basis, wording, List.of(RIB, ZRB, RIB_PDF), RIB_FROM, book, Kind.EXCEPTION,
                Optional.of(displaces), category == null ? Set.of() : Set.of(category), individual, formula,
                Optional.empty(), Set.of(), Set.of(), locator(wording));
    }

    /** Fundstelle von Nr. 6 (S. 8) und Nr. 8 (S. 9) in der amtlichen PDF-Fassung. */
    private static String locator(String wording) {
        return wording.equals(NR6) ? "VO/VW-RiB Nr. 6, S. 8" : "VO/VW-RiB Nr. 8, S. 9";
    }

    /** Anlage 2: Fristaufhebung für Abschnitt 7 bzw. 8 des Verwahrungsbuchs, nach Nr. 10 befristet bis Ablauf 2024. */
    private static DeadlineRule section(String id, String section, String bookingPoint) {
        return new DeadlineRule(id, "1", "Anlage 2 Abschnitt " + section + " i. V. m. Nr. 10 VO/VW-RiB",
                "Anlage 2, Abschnitt " + section + ": „" + ANL2_ABOLISHED + "“ Nr. 10: „" + NR10_LIMIT + "“",
                List.of(RIB_PDF, RIB), RIB_FROM, BOOK_CUSTODY, Kind.EXCEPTION, Optional.of("rib-8-verwahrung"),
                Set.of(), false, Formula.EXEMPT, SECTIONS_7_8_UNTIL, Set.of(bookingPoint), Set.of(section),
                "VO/VW-RiB Anlage 2, S. 14; befristet durch Nr. 10, S. 10");
    }

    /** Nr. 5.5 Abs. 3 Bewi-Bund: Verfahrenspflicht, keine eigene Ausgleichsfrist; gilt ab 01.01.2027. */
    private static DeadlineRule bewi(String id, String book) {
        return new DeadlineRule(id, "1", "Nr. 5.5 Abs. 3 Bewi-Bund", BEWI_5_5_3, List.of(BEWI_PDF, BEWI_FROM_SOURCE),
                BEWI_FROM, book, Kind.PROCEDURE, Optional.empty(), Set.of(), false, Formula.NONE, Optional.empty(),
                Set.of(), Set.of(), "Bewi-Bund Nr. 5.5 Abs. 3, S. 23");
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public List<DeadlineRule> rules() {
        return RULES;
    }
}
