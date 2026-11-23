package com.aresstack.spaceharvester.application.deadlines;

import java.util.List;

/**
 * Feste fachliche Schlüssel und Gruppen der Fristenextraktion (ADR 0035, Abschnitt 3). Regex und KI nutzen
 * dieselben Schlüssel, damit {@code FieldMerge} gleiche Angaben als dasselbe Feld erkennt. Schwerpunkt sind die
 * Merkmale, an denen eine Ausnahme von der Standardfrist erkennbar ist.
 */
public final class DeadlineFieldKeys {

    private DeadlineFieldKeys() {
    }

    // Dokument
    public static final String DOCUMENT_KIND = "dokument.art";
    public static final String DOCUMENT_DATE = "dokument.datum";
    public static final String ISSUER = "dokument.aussteller";
    public static final String DIVISION = "dokument.referat";
    public static final String FILE_REFERENCE = "dokument.geschaeftszeichen";
    public static final String DOK_ID = "dokument.dok-kennung";
    public static final String SUBJECT = "dokument.betreff";
    public static final String REFERENCED_DOCUMENT = "dokument.bezug";
    public static final String VALID_FROM = "dokument.geltung-beginn";
    public static final String VALID_UNTIL = "dokument.geltung-ende";

    // Konten- und Haushaltsmerkmale (je Sachverhalt)
    public static final String MANAGER_NUMBER = "konto.bewirtschafternummer";
    public static final String MANAGER = "konto.bewirtschafter";
    public static final String FUNDS_DISTRIBUTOR = "konto.mittelverteiler";
    public static final String TITLE_ADMINISTRATOR = "konto.titelverwalter";
    /** Vorschussbuch oder Verwahrungsbuch; Werte {@link #BOOK_ADVANCE}, {@link #BOOK_CUSTODY}. */
    public static final String BOOK = "konto.buch";
    public static final String BOOKING_POINT = "konto.buchungsstelle";
    public static final String SECTION = "konto.abschnitt";
    public static final String SUBSECTION = "konto.unterabschnitt";
    public static final String OBJECT_ACCOUNT = "konto.objektkonto";
    public static final String LEDGER_ACCOUNT = "konto.sachbuchkonto";
    public static final String BUDGET_ITEM = "konto.haushaltsstelle";
    /** Einzel- oder Sammelkonto; Werte {@link #ACCOUNT_SINGLE}, {@link #ACCOUNT_COLLECTIVE}. */
    public static final String ACCOUNT_SCOPE = "konto.umfang";
    public static final String ACCOUNT_PURPOSE = "konto.zweck";
    /** Fachliche Kategorie, etwa durchlaufende Gelder oder Sockelbetrag einer Geldstelle. */
    public static final String ACCOUNT_CATEGORY = "konto.kategorie";

    // Regelungsinhalte (je Fristaussage eine Gruppe, siehe groupOf)
    public static final String DEADLINE_STATEMENT = "frist.aussage";
    public static final String DEADLINE_START = "frist.beginn";
    public static final String DEADLINE_DURATION = "frist.dauer";
    /** Nur wenn das Dokument das Ende selbst nennt; nie berechnet. */
    public static final String DEADLINE_END = "frist.ende";
    /** Bezug der Frist; Werte {@link #REFERS_TO_PAYMENT}, {@link #REFERS_TO_ACCOUNT}. */
    public static final String DEADLINE_REFERENCE = "frist.bezug";
    public static final String DEADLINE_CONDITION = "frist.bedingung";
    public static final String DEADLINE_EXCEPTION = "frist.ausnahme";
    /** Verlängerung; beantragt oder genehmigt steht im Qualifier, nie im Wert. */
    public static final String DEADLINE_EXTENSION = "frist.verlaengerung";
    public static final String BOOKING_RULE = "regelung.buchung";
    public static final String SETTLEMENT_RULE = "regelung.ausgleich";
    public static final String LEGAL_REFERENCE = "regelung.verweis";

    // Normalisierte Werte
    public static final String BOOK_ADVANCE = "vorschuss";
    public static final String BOOK_CUSTODY = "verwahrung";
    public static final String ACCOUNT_SINGLE = "einzelkonto";
    public static final String ACCOUNT_COLLECTIVE = "sammelkonto";
    public static final String REFERS_TO_PAYMENT = "einzelne-zahlung";
    public static final String REFERS_TO_ACCOUNT = "konto";
    public static final String CATEGORY_PASS_THROUGH = "durchlaufende-gelder";

    /** Schlüssel, die zu genau einer Fristaussage gehören und darum eine Gruppe tragen. */
    public static final List<String> DEADLINE_GROUP_KEYS = List.of(DEADLINE_STATEMENT, DEADLINE_START,
            DEADLINE_DURATION, DEADLINE_END, DEADLINE_REFERENCE, DEADLINE_CONDITION, DEADLINE_EXCEPTION,
            DEADLINE_EXTENSION);

    /** Gruppe der n-ten Fristaussage eines Sachverhalts (ab 1), z. B. {@code frist-2}. */
    public static String groupOf(int statement) {
        if (statement < 1) {
            throw new IllegalArgumentException("Fristaussagen zählen ab 1");
        }
        return "frist-" + statement;
    }
}
