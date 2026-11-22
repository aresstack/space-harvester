package com.aresstack.spaceharvester.deck.ftp.jes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest die Antworten des JES-FTP-Servers: Jobliste, Spool-Liste, Abschnitte der Gesamtausgabe, Job-ID nach dem
 * Einreichen. Übernommen aus MainframeMate ({@code JesFtpService}, {@code JesFtpJobSubmitter}), dort gegen echte Hosts
 * erprobt; ergänzt um Spool-Zeilen ohne Prozedurschritt, die MainframeMate falsch zuordnete.
 */
public final class JesListing {

    private static final String JOB_ID = "(?:JOB|STC|TSU|J|S|T)\\d{1,8}";

    /** {@code JOBNAME  JOB12345 OWNER    OUTPUT  A        RC=0000 3 spool files} (JESINTERFACELEVEL=2). */
    private static final Pattern JOB_LINE = Pattern.compile(
            "^(\\S+)\\s+(" + JOB_ID + ")\\s+(\\S+)\\s+(\\S+)\\s+(\\S*)\\s*(?:RC=(\\S+))?.*?(\\d+)\\s+spool",
            Pattern.CASE_INSENSITIVE);

    /** Einfacher: Name, ID, Besitzer, Zustand. */
    private static final Pattern JOB_LINE_SIMPLE = Pattern.compile(
            "^(\\S+)\\s+(" + JOB_ID + ")\\s+(\\S+)\\s+(OUTPUT|ACTIVE|INPUT|HELD)(?:\\s+(\\S))?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ABEND = Pattern.compile("ABEND[=\\s]+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBMITTED_ID = Pattern.compile("\\b(?:JOB|STC|TSU|J)\\d{1,8}\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JOB_CARD = Pattern.compile("^//([A-Z$#@][A-Z0-9$#@]{0,7})\\s+JOB\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DSN_WITH_QUESTION = Pattern.compile(
            "data\\s+set\\s+\\S+\\.\\?\\.(\\S+?)(?:\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DSN = Pattern.compile("data\\s+set\\s+(\\S+?)(?:\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final String[] KNOWN_DD_NAMES = {"JESMSGLG", "JESJCL", "JESYSMSG", "SYSPRINT", "SYSOUT",
            "SYSTSPRT", "SYSIN"};

    private JesListing() {
    }

    /** Eine Zeile der Jobliste; {@code null} für Kopfzeilen und Unlesbares. */
    public static JesJob parseJob(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String trimmed = line.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.startsWith("JOBNAME") || upper.startsWith("---") || upper.startsWith("OWNER")) {
            return null;
        }
        Matcher full = JOB_LINE.matcher(trimmed);
        if (full.find()) {
            String code = full.group(6);
            String jobClass = full.group(5);
            if (jobClass.contains("=")) {
                // Ohne Klasse rutscht „RC=0000“ oder „ABEND=S0C4“ an ihre Stelle.
                if (code == null && jobClass.toUpperCase(Locale.ROOT).startsWith("RC=")) {
                    code = jobClass.substring(3);
                }
                jobClass = "";
            }
            return new JesJob(full.group(2).toUpperCase(Locale.ROOT), full.group(1), full.group(3),
                    full.group(4).toUpperCase(Locale.ROOT), jobClass,
                    Optional.ofNullable(code != null ? code : returnCodeFromTail(trimmed)), parseInt(full.group(7)));
        }
        Matcher simple = JOB_LINE_SIMPLE.matcher(trimmed);
        if (simple.find()) {
            return new JesJob(simple.group(2).toUpperCase(Locale.ROOT), simple.group(1), simple.group(3),
                    simple.group(4).toUpperCase(Locale.ROOT), simple.group(5) == null ? "" : simple.group(5),
                    Optional.ofNullable(returnCodeFromTail(trimmed)), 0);
        }
        return null;
    }

    private static String returnCodeFromTail(String line) {
        Matcher abend = ABEND.matcher(line);
        if (abend.find()) {
            return "ABEND " + abend.group(1);
        }
        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.contains("JCL ERROR")) {
            return "JCL ERROR";
        }
        return null;
    }

    /**
     * Eine Zeile der Spool-Liste ({@code LIST jobId}): {@code ID STEP [PROC] C DDNAME BYTES [RECORDS]}. Der
     * Prozedurschritt fehlt oft; die Ausgabeklasse (ein Zeichen) zeigt, wo der DD-Name steht.
     */
    public static SpoolFile parseSpool(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String upper = line.trim().toUpperCase(Locale.ROOT);
        if (upper.startsWith("ID") || upper.startsWith("---") || upper.startsWith("JOBNAME") || upper.startsWith("BYTE")
                || upper.contains("SPOOL FILE")) {
            return null;
        }
        String[] tokens = line.trim().split("\\s+");
        if (tokens.length < 2 || !tokens[0].matches("\\d{1,4}")) {
            return null;
        }
        int id = Integer.parseInt(tokens[0]);
        int classAt = -1;
        if (tokens.length > 4 && isClass(tokens[3])) {
            classAt = 3;
        } else if (tokens.length > 3 && isClass(tokens[2])) {
            classAt = 2;
        }
        if (classAt < 0) {
            // Unbekannter Aufbau: wie MainframeMate den fünften (sonst letzten) Teil als DD-Namen nehmen.
            String ddName = tokens.length >= 5 ? tokens[4] : tokens[tokens.length - 1];
            return new SpoolFile(id, ddName.matches("[\\d,]+") ? null : ddName, tokens.length >= 2 ? tokens[1] : "",
                    "", "", 0, 0);
        }
        String step = tokens[1];
        String proc = classAt == 3 ? tokens[2] : "";
        String ddName = tokens[classAt + 1];
        long bytes = tokens.length > classAt + 2 ? parseLong(tokens[classAt + 2].replace(",", "")) : 0;
        int records = tokens.length > classAt + 3 ? parseInt(tokens[classAt + 3]) : 0;
        return new SpoolFile(id, ddName, step, proc, tokens[classAt], bytes, records);
    }

    private static boolean isClass(String token) {
        return token.length() == 1 && Character.isLetterOrDigit(token.charAt(0));
    }

    /** Die Abschnitte der Gesamtausgabe, getrennt durch {@code !! END OF JES SPOOL FILE !!}. */
    public static List<SpoolFile> sections(String whole) {
        List<SpoolFile> sections = new ArrayList<>();
        List<List<String>> parts = split(whole);
        for (int i = 0; i < parts.size(); i++) {
            List<String> part = parts.get(i);
            sections.add(new SpoolFile(i + 1, ddNameFromContent(String.join("\n", part), i + 1), "", "", "", 0,
                    part.size()));
        }
        return sections;
    }

    /** Der Text des Abschnitts {@code id} (ab 1) der Gesamtausgabe; leer, wenn es ihn nicht gibt. */
    public static String sectionText(String whole, int id) {
        List<List<String>> parts = split(whole);
        if (id < 1 || id > parts.size()) {
            return "";
        }
        return String.join("\n", parts.get(id - 1));
    }

    private static List<List<String>> split(String whole) {
        List<List<String>> parts = new ArrayList<>();
        if (whole == null || whole.isEmpty()) {
            return parts;
        }
        String[] lines = whole.split("\\r?\\n");
        List<String> current = null;
        for (String line : lines) {
            if (isSeparator(line)) {
                if (current != null && !current.isEmpty()) {
                    parts.add(current);
                }
                current = null;
                continue;
            }
            if (current == null) {
                if (line.isBlank()) {
                    continue;
                }
                current = new ArrayList<>();
            }
            current.add(line);
        }
        if (current != null && !current.isEmpty()) {
            parts.add(current);
        }
        return parts;
    }

    private static boolean isSeparator(String line) {
        String trimmed = line.trim();
        return trimmed.contains("END OF JES SPOOL FILE") || trimmed.contains("END OF JES2 SPOOL")
                || trimmed.matches("^!!\\s+END\\s+.*!!$");
    }

    private static String ddNameOfLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String upper = line.trim().toUpperCase(Locale.ROOT);
        String compact = upper.replaceAll("\\s+", "");
        if (compact.contains("JES2JOBLOG") || compact.contains("JESJOBLOG")) {
            return "JESMSGLG";
        }
        if (compact.contains("JES2JCL") || compact.contains("JESJCL")) {
            return "JESJCL";
        }
        if (compact.contains("SYSTEMMESSAGES") || compact.contains("JESYSMSG")) {
            return "JESYSMSG";
        }
        if (compact.contains("SYSPRINT")) {
            return "SYSPRINT";
        }
        if (compact.contains("SYSOUT")) {
            return "SYSOUT";
        }
        if (compact.contains("SYSTSPRT")) {
            return "SYSTSPRT";
        }
        // JCL, auch mit vorangestellter Anweisungsnummer wie im Spool („    1 //BUILD JOB …“)
        if (upper.matches("^(\\d+\\s+)?//.*")) {
            return "JESJCL";
        }
        return null;
    }

    /** Erkennt den DD-Namen am Inhalt einer Spool-Datei (Verfahren „PROBE“ aus MainframeMate). */
    public static String ddNameFromContent(String content, int spoolId) {
        if (content == null || content.isEmpty()) {
            return "SPOOL#" + spoolId;
        }
        String[] lines = content.split("\\r?\\n");
        int end = Math.min(lines.length, 30);
        for (int i = 0; i < end; i++) {
            String candidate = ddNameOfLine(lines[i]);
            if (candidate != null) {
                return candidate;
            }
        }
        for (int i = 0; i < end; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("STMT NO.") || trimmed.startsWith("STMT  NO.")
                    || trimmed.matches("^\\d+\\s+IEF[A-Z]?\\d+.*") || trimmed.matches("^\\d+\\s+IGD\\d+.*")
                    || trimmed.startsWith("ICH70001I") || trimmed.startsWith("IEF")) {
                return "JESYSMSG";
            }
        }
        for (int i = 0; i < end; i++) {
            String trimmed = lines[i].trim();
            if ((trimmed.contains("IDCAMS") && trimmed.contains("SYSTEM SERVICES")) || trimmed.startsWith("IDC0")
                    || trimmed.startsWith("IDC1")) {
                return "SYSPRINT";
            }
        }
        return "SPOOL#" + spoolId;
    }

    /** Der DD-Name aus der Antwort auf {@code RETR jobId.n}. */
    public static String ddNameFromReply(String reply, int spoolId) {
        if (reply == null || reply.isEmpty()) {
            return "SPOOL#" + spoolId;
        }
        Matcher withQuestion = DSN_WITH_QUESTION.matcher(reply);
        if (withQuestion.find()) {
            return withQuestion.group(1).toUpperCase(Locale.ROOT);
        }
        Matcher dsn = DSN.matcher(reply);
        if (dsn.find()) {
            String name = dsn.group(1);
            int lastDot = name.lastIndexOf('.');
            if (lastDot >= 0 && lastDot < name.length() - 1) {
                return name.substring(lastDot + 1).toUpperCase(Locale.ROOT);
            }
        }
        String upper = reply.toUpperCase(Locale.ROOT);
        for (String dd : KNOWN_DD_NAMES) {
            if (upper.contains(dd)) {
                return dd;
            }
        }
        return "SPOOL#" + spoolId;
    }

    /** Die Job-ID aus der Antwort nach dem Einreichen, etwa {@code 250-It is known to JES as JOB12345}. */
    public static Optional<String> submittedJobId(String reply) {
        if (reply == null) {
            return Optional.empty();
        }
        Matcher matcher = SUBMITTED_ID.matcher(reply);
        return matcher.find() ? Optional.of(matcher.group().toUpperCase(Locale.ROOT)) : Optional.empty();
    }

    /** Der Jobname aus der ersten JOB-Karte. */
    public static Optional<String> jobName(String jcl) {
        if (jcl == null) {
            return Optional.empty();
        }
        for (String line : jcl.split("\\r?\\n", 50)) {
            Matcher matcher = JOB_CARD.matcher(line);
            if (matcher.find()) {
                return Optional.of(matcher.group(1).toUpperCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    private static int parseInt(String text) {
        try {
            return text == null ? 0 : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String text) {
        try {
            return text == null ? 0 : Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
