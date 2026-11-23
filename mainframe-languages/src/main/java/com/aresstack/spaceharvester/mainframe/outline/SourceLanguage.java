package com.aresstack.spaceharvester.mainframe.outline;

import com.aresstack.spaceharvester.mainframe.parser.DdmOutlineBuilder;
import com.aresstack.spaceharvester.mainframe.parser.DdmParser;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Wie der Quelltext geparst wird. In MainframeMate steuerte das die Satzarten-Auswahl (Satzart „JCL“, „COBOL“,
 * „NATURAL“, „DDM“ als Sprachhinweis für die Gliederung); ohne Hinweis erkannte MainframeMate die Sprache an Endung
 * und Inhalt. Beides steckt hier, ohne Satzarten.
 */
public enum SourceLanguage {

    JCL("JCL"),
    COBOL("COBOL"),
    NATURAL("Natural"),
    DDM("Natural-DDM");

    /** Natural-Endungen aus {@code NaturalAnalysisService} und {@code SplitPreviewTab} von MainframeMate. */
    private static final Set<String> NATURAL_EXTENSIONS = Set.of("nat", "nsp", "nsn", "nss", "nsh", "nsc", "nsl",
            "nsa", "nsg", "nsm", "ns3", "ns4", "ns5", "ns6", "ns7", "ns8");
    private static final Set<String> JCL_EXTENSIONS = Set.of("jcl", "proc", "prc", "cntl");
    private static final Set<String> COBOL_EXTENSIONS = Set.of("cbl", "cob", "cobol", "cpy");
    private static final String[] JCL_KEYWORDS = {"JOB", "EXEC", "DD", "PROC", "PEND", "SET", "JCLLIB", "INCLUDE"};

    private final String label;

    SourceLanguage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Erkennt die Sprache an der Endung, sonst am Inhalt; leer, wenn es keiner der Großrechner-Texte ist. */
    public static Optional<SourceLanguage> detect(String name, String content) {
        Optional<SourceLanguage> byName = byName(name);
        return byName.isPresent() ? byName : byContent(content);
    }

    public static Optional<SourceLanguage> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        if (DdmOutlineBuilder.isDdmFile(name)) {
            return Optional.of(DDM);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return Optional.empty();
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (NATURAL_EXTENSIONS.contains(extension)) {
            return Optional.of(NATURAL);
        }
        if (JCL_EXTENSIONS.contains(extension)) {
            return Optional.of(JCL);
        }
        if (COBOL_EXTENSIONS.contains(extension)) {
            return Optional.of(COBOL);
        }
        return Optional.empty();
    }

    /** Inhaltsprüfung in derselben Reihenfolge wie {@code JclOutlinePanel}: DDM, Natural, COBOL, JCL. */
    public static Optional<SourceLanguage> byContent(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        if (DdmParser.isDdmContent(content)) {
            return Optional.of(DDM);
        }
        if (isNatural(content)) {
            return Optional.of(NATURAL);
        }
        if (isCobol(content)) {
            return Optional.of(COBOL);
        }
        if (isJcl(content)) {
            return Optional.of(JCL);
        }
        return Optional.empty();
    }

    static boolean isNatural(String content) {
        int hits = 0;
        for (String line : content.split("\\r?\\n", 40)) {
            String t = line.trim().toUpperCase(Locale.ROOT);
            if (t.startsWith("DEFINE DATA") || t.startsWith("END-DEFINE") || t.startsWith("DEFINE SUBROUTINE")
                    || t.startsWith("CALLNAT ") || t.startsWith("END-SUBROUTINE") || t.startsWith("LOCAL USING")
                    || t.startsWith("PARAMETER USING") || t.startsWith("DECIDE ON") || t.startsWith("DECIDE FOR")
                    || t.startsWith("INPUT USING MAP") || t.startsWith("FETCH RETURN")) {
                hits++;
            }
        }
        return hits >= 2;
    }

    static boolean isCobol(String content) {
        for (String line : content.split("\\r?\\n", 30)) {
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.contains("IDENTIFICATION DIVISION") || upper.contains("PROCEDURE DIVISION")
                    || upper.contains("DATA DIVISION") || upper.contains("ENVIRONMENT DIVISION")
                    || upper.contains("WORKING-STORAGE SECTION") || upper.contains("PROGRAM-ID")) {
                return true;
            }
        }
        return false;
    }

    static boolean isJcl(String content) {
        int jclLines = 0;
        int keywords = 0;
        for (String line : content.split("\\r?\\n", 20)) {
            if (line.startsWith("//")) {
                jclLines++;
                String upper = line.toUpperCase(Locale.ROOT);
                for (String keyword : JCL_KEYWORDS) {
                    if (upper.contains(" " + keyword + " ") || upper.contains(" " + keyword + ",")
                            || upper.contains("//" + keyword + " ")) {
                        keywords++;
                        break;
                    }
                }
            }
        }
        return jclLines >= 2 && keywords >= 1;
    }
}
