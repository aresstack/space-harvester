package com.aresstack.spaceharvester.deck.betaview;

import java.util.List;

/**
 * Die Auswahllisten der BetaView-Suche, Wert für Wert aus MainframeMate übernommen ({@code FormFilterPanel},
 * {@code ExtensionFilterPanel}, {@code ReportFilterPanel}, {@code JobNameFilterPanel}, {@code WildcardFilterPanel};
 * dort gegen den echten Server erprobt). Hier stehen nur die Listen und wie aus der Auswahl der Wert für den Server
 * wird (und umgekehrt); die Oberfläche baut daraus ihre Felder.
 */
public final class FilterPatterns {

    /** Markiert in einer Liste den Eintrag „freier Wert“. */
    public static final String CUSTOM = "CUSTOM";

    /** Ein Eintrag einer Auswahlliste: Wert für den Server, Text für die Anzeige. */
    public record Option(String value, String label) {
        public Option {
            value = value == null ? "" : value;
            label = label == null ? "" : label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** FORM wie {@code FormFilterPanel}. */
    public static final List<Option> FORMS = List.of(
            new Option("*", "* – Keine Einschränkung"),
            new Option("APZC", "APZC – Produktion (alle Produktionsdaten)"),
            new Option("APZF", "APZF – Produktion (Format)"),
            new Option("ATZC", "ATZC – Test (Bewirtschafter-Test/Entwicklung)"),
            new Option("ATAB", "ATAB – Schulung"),
            new Option("APZD", "APZD – F13Z/F15Z Buchungsdaten (Produktion)"),
            new Option("ATZD", "ATZD – F13Z/F15Z Buchungsdaten (Test)"),
            new Option("APAB", "APAB – ZABAK (Basis)"),
            new Option(CUSTOM, "Custom – Freier Form-Wert"));

    /** JOBNAME wie {@code JobNameFilterPanel}. */
    public static final List<Option> JOB_NAMES = List.of(
            new Option("*", "* – Keine Einschränkung"),
            new Option("APABBBNK", "APABBBNK – Protokoll Einlieferungen/Kontoinfos (Prüfung Bundesbank-Dateien)"),
            new Option("APABHOLE", "APABHOLE – Protokoll Auslieferungen (Prüfung Bewirtschafter-Dateien)"),
            new Option("APABDAUB", "APABDAUB – Protokoll Auslagerung an HKR/ZÜV"),
            new Option(CUSTOM, "Custom – Freier Jobname"));

    /** Ordner, Fach, Empfänger wie {@code WildcardFilterPanel}: nur {@code *} oder ein freier Wert. */
    public static final List<Option> WILDCARD = List.of(
            new Option("*", "* – Keine Einschränkung"),
            new Option(CUSTOM, "Custom – Freier Wert"));

    /** Vorgaben ohne gespeicherte Auswahl, wie {@code BetaViewAppProperties} in MainframeMate. */
    public static final String DEFAULT_FAVORITE = "A158";
    public static final String DEFAULT_LOCALE = "de";
    public static final String DEFAULT_FORM = "APZF";
    public static final String DEFAULT_EXTENSION = "*";
    public static final String DEFAULT_REPORT = "*";
    public static final String DEFAULT_JOB_NAME = "*";
    public static final int DEFAULT_DAYS_BACK = 60;

    private FilterPatterns() {
    }

    /**
     * Wert eines Felds „Liste oder frei“ (Form, Jobname, Ordner …): der gewählte Eintrag, bei {@link #CUSTOM} der
     * freie Text. Leerer freier Text wird zu {@code *}, außer beim Jobnamen ({@code emptyAsWildcard} = false), wo
     * MainframeMate ihn leer lässt.
     */
    public static String choice(String selected, String custom, boolean emptyAsWildcard) {
        if (CUSTOM.equals(selected)) {
            String text = safe(custom);
            return text.isEmpty() && emptyAsWildcard ? "*" : text;
        }
        return selected == null || selected.isEmpty() ? "*" : selected;
    }

    /**
     * Ordnet einen Wert einer Liste zu: ein bekannter Eintrag (außer {@link #CUSTOM}), sonst {@link #CUSTOM} mit dem
     * Wert als freiem Text. Leer und {@code *} ergeben {@code *}.
     *
     * @return {@code [Eintrag, freier Text]}
     */
    public static String[] parseChoice(List<Option> options, String raw) {
        String value = safe(raw);
        if (value.isEmpty() || "*".equals(value)) {
            return new String[] {"*", ""};
        }
        for (Option option : options) {
            if (!CUSTOM.equals(option.value()) && option.value().equals(value)) {
                return new String[] {value, ""};
            }
        }
        return new String[] {CUSTOM, value};
    }

    // ---- EXTENSION ----

    /** Art der Extension wie die Karten in {@code ExtensionFilterPanel}. */
    public enum ExtensionMode {
        BK_FREE("BKnn… – Bundeskasse: BK + Kassen-Nr + Freitext (z. B. BK18F05*)"),
        BK_BANK("BKnnBANK – Bundeskasse: BK + Kassen-Nr + BANK (+ optional *)"),
        CONSTANT("Konstant – FA00BANK / WV00BANK / …"),
        BEW_FB("B<BEW-NR>F13FB – Faxfreigabe-Bestätigung"),
        BEW_LB("B<BEW-NR>F13LB – Buchungsdatei-Bestätigung"),
        CUSTOM("Custom – Freier Extension-Wert");

        private final String label;

        ExtensionMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Die festen Extensions der Art {@link ExtensionMode#CONSTANT}. */
    public static final List<Option> EXTENSION_CONSTANTS = List.of(
            new Option("FA00BANK", "FA00BANK – Finanzagentur"),
            new Option("WV00BANK", "WV00BANK – Wertpapierverwaltung"),
            new Option("PA00BANK", "PA00BANK – Patent- und Markenamt"),
            new Option("BW19BANK", "BW19BANK – ZID Stuttgart Branntwein-Steuer"),
            new Option("*", "* – Keine Einschränkung"));

    /**
     * Die Teile einer Extension.
     *
     * @param mode   Art
     * @param first  Kassen-Nr (BK…), Bewirtschafternummer (B…F13..), Konstante oder freier Wert
     * @param suffix Suffix bei BK…, sonst leer
     */
    public record Extension(ExtensionMode mode, String first, String suffix) {

        public Extension {
            mode = mode == null ? ExtensionMode.BK_FREE : mode;
            first = safe(first);
            suffix = safe(suffix);
        }

        /** Der Wert für den Server, genau wie {@code ExtensionFilterPanel.value()}. */
        public String value() {
            return switch (mode) {
                case BK_FREE -> "BK" + digitsOnly(first) + suffix;
                case BK_BANK -> "BK" + digitsOnly(first) + "BANK" + suffix;
                case CONSTANT -> first;
                case BEW_FB -> "B" + digitsOnly(first) + "F13FB";
                case BEW_LB -> "B" + digitsOnly(first) + "F13LB";
                case CUSTOM -> first;
            };
        }

        /** Zerlegt einen Wert wie {@code ExtensionFilterPanel.applyInitialValue}; leer ergibt die Konstante „*“. */
        public static Extension parse(String raw) {
            String v = safe(raw);
            if (v.isEmpty()) {
                v = DEFAULT_EXTENSION;
            }
            for (Option constant : EXTENSION_CONSTANTS) {
                if (constant.value().equals(v)) {
                    return new Extension(ExtensionMode.CONSTANT, v, "");
                }
            }
            if (v.startsWith("B") && v.endsWith("F13FB")) {
                return new Extension(ExtensionMode.BEW_FB, v.substring(1, v.length() - "F13FB".length()), "");
            }
            if (v.startsWith("B") && v.endsWith("F13LB")) {
                return new Extension(ExtensionMode.BEW_LB, v.substring(1, v.length() - "F13LB".length()), "");
            }
            if (v.startsWith("BK") && v.contains("BANK")) {
                int bank = v.indexOf("BANK");
                return new Extension(ExtensionMode.BK_BANK, v.substring(2, bank), v.substring(bank + "BANK".length()));
            }
            if (v.startsWith("BK") && v.length() >= 4) {
                return new Extension(ExtensionMode.BK_FREE, v.substring(2, 4), v.substring(4));
            }
            return new Extension(ExtensionMode.CUSTOM, v, "");
        }
    }

    // ---- REPORT ----

    /** Art des Reports wie die Karten in {@code ReportFilterPanel}. */
    public enum ReportMode {
        ANY("Report: * – Keine Einschränkung"),
        PREFIX("Report: *A/*E/*K – nach Kategorie filtern"),
        BA("Report: BAxxx + Typ – ZABAK Listen"),
        K9999("Report: K9999BAnnni – gezielter Report"),
        BEWIRT("Report: Bewirtschafternummer"),
        CUSTOM("Report: Custom – freier Wert");

        private final String label;

        ReportMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final List<Option> REPORT_PREFIXES = List.of(
            new Option("*A", "*A – Alle Auslieferungen"),
            new Option("*E", "*E – Alle Einlieferungen"),
            new Option("*K", "*K – Alle Kontoinformationen"));

    public static final List<Option> BA_LISTS = List.of(
            new Option("BA001", "BA001 – Protokolle Ladeprogramm"),
            new Option("BA002", "BA002 – Protokolle Entladeprogramm"),
            new Option("BA100", "BA100 – Einzelsatz (Dialog)"),
            new Option("BA101", "BA101 – Sendung (Dialog)"),
            new Option("BA200", "BA200 – Statistik (Dialog)"));

    public static final List<Option> K_LISTS = List.of(
            new Option("001", "001 – BA001 (Ladeprogramm)"),
            new Option("002", "002 – BA002 (Entladeprogramm)"),
            new Option("100", "100 – BA100 (Einzelsatz)"),
            new Option("101", "101 – BA101 (Sendung)"),
            new Option("200", "200 – BA200 (Statistik)"));

    public static final List<Option> K_TYPES = List.of(
            new Option("A", "A – Auslieferung"),
            new Option("E", "E – Einlieferung"),
            new Option("K", "K – Kontoinformation"));

    /** Die Typen zu einer BA-Liste wie {@code ReportFilterPanel.updateBaTypes}. */
    public static List<Option> baTypes(String baList) {
        return switch (safe(baList)) {
            case "BA001" -> List.of(new Option("A", "A – PROTOKOLL AUSLIEFERUNGEN"),
                    new Option("E", "E – PROTOKOLL EINLIEFERUNGEN"),
                    new Option("K", "K – PROTOKOLL KONTOINFORMATIONEN"),
                    new Option("L", "L – ABSTIMMUNG HKR"));
            case "BA002" -> List.of(new Option("B", "B – PROTOKOLL EXPORT BBK"),
                    new Option("F", "F – PROTOKOLL EXPORT BFF"),
                    new Option("V", "V – VERWAHRFÄLLE EINZELLISTE"));
            case "BA100" -> List.of(new Option("A", "A – EINZELSATZ AUSLIEFERUNG"),
                    new Option("E", "E – EINZELSATZ EINLIEFERUNG"),
                    new Option("K", "K – EINZELSATZ KONTOINFORMATIONEN"));
            case "BA101" -> List.of(new Option("A", "A – SENDUNG AUSLIEFERUNG"),
                    new Option("E", "E – SENDUNG EINLIEFERUNG"));
            case "BA200" -> List.of(new Option("A", "A – STATISTIK AUSLIEFERUNG"),
                    new Option("E", "E – STATISTIK EINLIEFERUNG"));
            default -> K_TYPES;
        };
    }

    /**
     * Die Teile eines Reports.
     *
     * @param mode   Art
     * @param first  Präfix (*A …), BA-Liste, K-Liste (3 Ziffern), Bewirtschafternummer oder freier Wert
     * @param type   Typ bei BA und K9999, sonst leer
     */
    public record Report(ReportMode mode, String first, String type) {

        public Report {
            mode = mode == null ? ReportMode.ANY : mode;
            first = safe(first);
            type = safe(type);
        }

        /** Der Wert für den Server, genau wie {@code ReportFilterPanel.value()}. */
        public String value() {
            return switch (mode) {
                case ANY -> "*";
                case PREFIX -> first;
                case BA -> first + type;
                case K9999 -> "K9999" + "BA" + digitsOnly(first) + type;
                case BEWIRT -> first.isEmpty() ? "*" : "%" + first + "*";
                case CUSTOM -> first;
            };
        }

        /** Zerlegt einen Wert wie {@code ReportFilterPanel.applyInitialValue}; leer ergibt „*“. */
        public static Report parse(String raw) {
            String v = safe(raw);
            if (v.isEmpty() || "*".equals(v)) {
                return new Report(ReportMode.ANY, "", "");
            }
            if ("*A".equals(v) || "*E".equals(v) || "*K".equals(v)) {
                return new Report(ReportMode.PREFIX, v, "");
            }
            if (v.startsWith("BA") && v.length() == 6) {
                return new Report(ReportMode.BA, v.substring(0, 5), v.substring(5));
            }
            // MainframeMate prüft hier Länge 10; K9999BA + 3 Ziffern + Typ sind aber 11 Zeichen.
            if (v.startsWith("K9999BA") && v.length() == 11) {
                return new Report(ReportMode.K9999, v.substring(7, 10), v.substring(10));
            }
            if ((v.startsWith("%") || v.startsWith("*")) && v.endsWith("*") && v.length() > 2) {
                return new Report(ReportMode.BEWIRT, v.substring(1, v.length() - 1), "");
            }
            return new Report(ReportMode.CUSTOM, v, "");
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String digitsOnly(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }
}
