package com.aresstack.spaceharvester.deck.betaview;

/**
 * Die Auswahl einer BetaView-Suche, Feld für Feld wie das Formular {@code result.action} des Servers und wie
 * {@code ResultFilter} in MainframeMate (dort gegen den echten Server erprobt). Leere Angaben werden zu den Vorgaben
 * des Servers: {@code *} für Platzhalterfelder, leer für Statusfelder.
 *
 * @param favoriteId Favorit (Auswahlmaske) des Servers; Pflicht
 * @param locale     Sprache der Oberfläche, etwa {@code de}; Pflicht
 * @param period     Zeitraum
 * @param form       FORM
 * @param extension  EXTENSION
 * @param report     REPORT
 * @param jobName    JOBNAME
 * @param folder     FOLDER (Ordner)
 * @param tab        TAB (Fach)
 * @param title      TITLE
 * @param exactTitle FTITLE=1
 * @param recipient  RECI (Empfänger)
 * @param status     Statusfelder (ONLINE, LGRNOTE …)
 */
public record SearchFilter(String favoriteId, String locale, Period period, String form, String extension,
                           String report, String jobName, String folder, String tab, String title,
                           boolean exactTitle, String recipient, Status status) {

    public SearchFilter {
        favoriteId = required(favoriteId, "Favorit fehlt");
        locale = required(locale, "Sprache fehlt");
        period = period == null ? Period.lastSevenDays() : period;
        form = wildcard(form);
        extension = wildcard(extension);
        report = wildcard(report);
        jobName = wildcard(jobName);
        folder = wildcard(folder);
        tab = wildcard(tab);
        title = title == null ? "" : title.trim();
        recipient = wildcard(recipient);
        status = status == null ? Status.any() : status;
    }

    /** Ob der Server {@code SELGEN=yes} braucht: sobald Ordner, Fach, Titel oder Empfänger eingeschränkt sind. */
    public boolean needsSelgen() {
        return !"*".equals(folder) || !"*".equals(tab) || !title.isEmpty() || !"*".equals(recipient);
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String wildcard(String value) {
        return value == null || value.isBlank() ? "*" : value.trim();
    }

    /**
     * Zeitraum: fest ({@code last7days}, {@code today}, {@code yesterday}), individuell (Zahl und Einheit) oder ein
     * Datumsbereich von–bis ({@code TT.MM.JJJJ}).
     *
     * @param mode   Art
     * @param amount Zahl bei {@link Mode#INDIVIDUAL}
     * @param unit   Einheit bei {@link Mode#INDIVIDUAL}
     * @param from   Von-Datum bei {@link Mode#DATE_RANGE}
     * @param to     Bis-Datum bei {@link Mode#DATE_RANGE}
     */
    public record Period(Mode mode, int amount, Unit unit, String from, String to) {

        /** Ohne Angabe schickt MainframeMate 60 als Zahl mit. */
        public static final int DEFAULT_AMOUNT = 60;

        public Period {
            mode = mode == null ? Mode.LAST_7_DAYS : mode;
            amount = amount > 0 ? amount : DEFAULT_AMOUNT;
            unit = unit == null ? Unit.DAYS : unit;
            from = from == null ? "" : from.trim();
            to = to == null ? "" : to.trim();
            if (mode == Mode.DATE_RANGE && from.isEmpty() && to.isEmpty()) {
                throw new IllegalArgumentException("Für den Datumsbereich fehlen Von und Bis");
            }
        }

        public static Period lastSevenDays() {
            return new Period(Mode.LAST_7_DAYS, DEFAULT_AMOUNT, Unit.DAYS, "", "");
        }

        /** {@code lastsel}: beim Datumsbereich erwartet der Server {@code individual}. */
        public String lastsel() {
            return mode == Mode.DATE_RANGE ? "individual" : mode.value;
        }

        /** {@code lastdate}: {@code date} beim Datumsbereich, sonst {@code last}. */
        public String lastdate() {
            return mode == Mode.DATE_RANGE ? "date" : "last";
        }

        public String dateFrom() {
            return mode == Mode.DATE_RANGE ? from : "";
        }

        public String dateTo() {
            return mode == Mode.DATE_RANGE ? to : "";
        }

        /** Art des Zeitraums mit dem Wert des Servers. */
        public enum Mode {
            LAST_7_DAYS("last7days", "Letzte 7 Tage"),
            TODAY("today", "Heute"),
            YESTERDAY("yesterday", "Gestern"),
            INDIVIDUAL("individual", "Individuell"),
            DATE_RANGE("daterange", "Datumsbereich (von – bis)");

            private final String value;
            private final String label;

            Mode(String value, String label) {
                this.value = value;
                this.label = label;
            }

            public String value() {
                return value;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        /** Einheit des individuellen Zeitraums ({@code timeunit}). */
        public enum Unit {
            DAYS("days", "Tage"),
            HOURS("hours", "Stunden"),
            MINUTES("minutes", "Minuten");

            private final String value;
            private final String label;

            Unit(String value, String label) {
                this.value = value;
                this.label = label;
            }

            public String value() {
                return value;
            }

            @Override
            public String toString() {
                return label;
            }
        }
    }

    /**
     * Statusfelder der Suche; leer heißt „alle“. Ja/Nein-Felder nehmen {@code JA} oder {@code NEIN}.
     *
     * @param online   ONLINE
     * @param note     LGRNOTE (Notiz)
     * @param read     LGRXREAD (gelesen)
     * @param archive  ARCHIVE
     * @param process  PROCESS: {@code LIST} oder {@code REPORT}
     * @param delete   DELETE
     * @param listState LGRSTAT: {@code H}, {@code C}, {@code N} oder {@code T}
     * @param reload   RELOAD
     */
    public record Status(String online, String note, String read, String archive, String process, String delete,
                         String listState, String reload) {

        public Status {
            online = clean(online);
            note = clean(note);
            read = clean(read);
            archive = clean(archive);
            process = clean(process);
            delete = clean(delete);
            listState = clean(listState);
            reload = clean(reload);
        }

        public static Status any() {
            return new Status("", "", "", "", "", "", "", "");
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
