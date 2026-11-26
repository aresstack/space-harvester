package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognize mainframe program sources by content instead of dataset naming conventions. */
public final class FtpProgramClassifier {

    public enum Kind {
        COBOL, COPYBOOK, JCL, UNKNOWN
    }

    private static final Pattern DIVISION = Pattern.compile(
            "^(?:IDENTIFICATION|ID|ENVIRONMENT|DATA|PROCEDURE)\\s+DIVISION\\b.*");
    private static final Pattern PROGRAM_ID = Pattern.compile("^PROGRAM-ID\\s*\\..*");
    private static final Pattern DATA = Pattern.compile(
            "^(?:0[1-9]|[1-4][0-9]|66|77|78|88)\\s+[A-Z][A-Z0-9-]*(?:\\s+.*|\\.)$");
    private static final Pattern DATA_CLAUSE = Pattern.compile(
            "\\b(?:PIC|PICTURE|REDEFINES|OCCURS|USAGE|VALUE|COMP(?:-[1-5])?)\\b");
    private static final Pattern JCL = Pattern.compile(
            "^//(?:[A-Z@#$][A-Z0-9@#$]{0,7})?\\s+(JOB|EXEC|DD|PROC|PEND|SET|JCLLIB|INCLUDE)(?:[\\s,].*|$)");
    private static final Pattern FIXED_PREFIX = Pattern.compile("^[0-9 ]{6}[ */D-].*");

    private FtpProgramClassifier() {
    }

    public static Kind classify(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Kind.UNKNOWN;
        }
        int declarations = 0;
        int jclStatements = 0;
        boolean dataClause = false;
        boolean cobol = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.toUpperCase(Locale.ROOT);
            if (line.startsWith("//*")) {
                continue;
            }
            Matcher jcl = JCL.matcher(line);
            if (jcl.matches()) {
                jclStatements++;
                String statement = jcl.group(1);
                if ("JOB".equals(statement) || "EXEC".equals(statement) || "PROC".equals(statement)) {
                    return Kind.JCL;
                }
                continue;
            }
            String code = cobolCode(line);
            cobol |= DIVISION.matcher(code).matches() || PROGRAM_ID.matcher(code).matches();
            if (DATA.matcher(code).matches()) {
                declarations++;
                dataClause |= DATA_CLAUSE.matcher(code).find();
            }
        }
        if (cobol) {
            return Kind.COBOL;
        }
        if (jclStatements >= 2) {
            return Kind.JCL;
        }
        if (declarations >= 2 || declarations == 1 && dataClause) {
            return Kind.COPYBOOK;
        }
        return Kind.UNKNOWN;
    }

    private static String cobolCode(String line) {
        if (FIXED_PREFIX.matcher(line).matches()) {
            char indicator = line.charAt(6);
            if (indicator == '*' || indicator == '/') {
                return "";
            }
            line = line.substring(7, Math.min(72, line.length()));
        }
        String code = line.trim();
        return code.startsWith("*>") ? "" : code;
    }
}