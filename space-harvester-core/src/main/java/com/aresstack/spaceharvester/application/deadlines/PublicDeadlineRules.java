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
 * und Nr. 6 und Nr. 8 der Vorschuss- und Verwahrungsrichtlinie des Bundes (VO/VW-RiB, Stand 04/21, GMBl 2021 Nr. 57,
 * S. 1246). Grundsatz und Ausnahmen getrennt; jede Ausnahme nennt die verdrängte Regel.
 */
public final class PublicDeadlineRules implements DeadlineRuleCatalog {

    /** Version des Katalogs. */
    public static final String VERSION = "public-1";

    static final String BHO = "https://www.gesetze-im-internet.de/bho/__60.html (abgerufen 23.11.2026)";
    static final String RIB = "https://www.verwaltungsvorschriften-im-internet.de/"
            + "bsvwvbund_01092021_IIE4H10052010006003.htm (abgerufen 23.11.2026)";
    static final String ZRB = "https://zrb.bund.de/vorschriften/verwaltungsvorschriften-zur-bho";
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

    private static final List<DeadlineRule> RULES = List.of(
            standard("rib-6-vorschuss", "Nr. 6 Satz 1 VO/VW-RiB", NR6, BOOK_ADVANCE),
            exception("rib-6-sockel", "Nr. 6 Satz 2 VO/VW-RiB", NR6, BOOK_ADVANCE, "rib-6-vorschuss",
                    CATEGORY_BASE_AMOUNT, false, Formula.EXEMPT),
            exception("rib-6-durchlaufend", "Nr. 6 Satz 2 und 3 VO/VW-RiB", NR6, BOOK_ADVANCE, "rib-6-vorschuss",
                    CATEGORY_PASS_THROUGH, false, Formula.END_OF_SECOND_FOLLOWING_YEAR),
            new DeadlineRule("bho-60-1-einwilligung", "1", "§ 60 Abs. 1 Satz 2 BHO", BHO_60_1, List.of(BHO),
                    BHO_FROM, BOOK_ADVANCE, Kind.EXCEPTION, Optional.of("rib-6-vorschuss"), Set.of(), true,
                    Formula.AS_GRANTED),
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
                    Formula.AS_GRANTED));

    private static DeadlineRule standard(String id, String basis, String wording, String book) {
        return new DeadlineRule(id, "1", basis, wording, List.of(RIB, ZRB), RIB_FROM, book, Kind.STANDARD,
                Optional.empty(), Set.of(), false, Formula.THREE_MONTHS_AFTER_ORIGIN_YEAR);
    }

    private static DeadlineRule exception(String id, String basis, String wording, String book, String displaces,
                                          String category, boolean individual, Formula formula) {
        return new DeadlineRule(id, "1", basis, wording, List.of(RIB, ZRB), RIB_FROM, book, Kind.EXCEPTION,
                Optional.of(displaces), category == null ? Set.of() : Set.of(category), individual, formula);
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
