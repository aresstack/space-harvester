package com.aresstack.spaceharvester.app.ui.deck.editor;

import java.util.Map;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

/** Welche Syntaxhervorhebung zu welcher Dateiendung gehört; Unbekanntes bleibt einfacher Text. */
public final class SyntaxStyles {

    /** Natural (Software AG), wie in MainframeMate ({@code MainframeSyntaxSupport.SYNTAX_STYLE_NATURAL}). */
    public static final String NATURAL = "text/natural";

    static {
        // Wie MainframeSyntaxSupport.register() in MainframeMate: der eigene TokenMaker für Natural.
        TokenMakerFactory factory = TokenMakerFactory.getDefaultInstance();
        if (factory instanceof AbstractTokenMakerFactory tokenMakers) {
            tokenMakers.putMapping(NATURAL, NaturalTokenMaker.class.getName());
        }
    }

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            // Natural-Objekte mit den Endungen von NaturalONE (ObjectType.getInstanceIdExtension).
            Map.entry("nsp", NATURAL), Map.entry("nsn", NATURAL), Map.entry("nss", NATURAL),
            Map.entry("nsh", NATURAL), Map.entry("nsc", NATURAL), Map.entry("nsm", NATURAL),
            Map.entry("nsl", NATURAL), Map.entry("nsg", NATURAL), Map.entry("nsa", NATURAL),
            Map.entry("nsd", NATURAL), Map.entry("ns4", NATURAL), Map.entry("ns3", NATURAL),
            Map.entry("nst", NATURAL), Map.entry("ns5", NATURAL), Map.entry("ns6", NATURAL),
            Map.entry("ns7", NATURAL), Map.entry("ns8", NATURAL),
            Map.entry("java", SyntaxConstants.SYNTAX_STYLE_JAVA),
            Map.entry("kt", SyntaxConstants.SYNTAX_STYLE_KOTLIN),
            Map.entry("groovy", SyntaxConstants.SYNTAX_STYLE_GROOVY),
            Map.entry("gradle", SyntaxConstants.SYNTAX_STYLE_GROOVY),
            Map.entry("xml", SyntaxConstants.SYNTAX_STYLE_XML),
            Map.entry("xsd", SyntaxConstants.SYNTAX_STYLE_XML),
            Map.entry("html", SyntaxConstants.SYNTAX_STYLE_HTML),
            Map.entry("htm", SyntaxConstants.SYNTAX_STYLE_HTML),
            Map.entry("css", SyntaxConstants.SYNTAX_STYLE_CSS),
            Map.entry("js", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
            Map.entry("ts", SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT),
            Map.entry("json", SyntaxConstants.SYNTAX_STYLE_JSON),
            Map.entry("yaml", SyntaxConstants.SYNTAX_STYLE_YAML),
            Map.entry("yml", SyntaxConstants.SYNTAX_STYLE_YAML),
            Map.entry("properties", SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE),
            Map.entry("ini", SyntaxConstants.SYNTAX_STYLE_INI),
            Map.entry("sql", SyntaxConstants.SYNTAX_STYLE_SQL),
            Map.entry("ddl", SyntaxConstants.SYNTAX_STYLE_SQL),
            Map.entry("py", SyntaxConstants.SYNTAX_STYLE_PYTHON),
            Map.entry("sh", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("bat", SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH),
            Map.entry("cmd", SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH),
            Map.entry("ps1", SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH),
            Map.entry("c", SyntaxConstants.SYNTAX_STYLE_C),
            Map.entry("h", SyntaxConstants.SYNTAX_STYLE_C),
            Map.entry("cpp", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
            Map.entry("cs", SyntaxConstants.SYNTAX_STYLE_CSHARP),
            Map.entry("md", SyntaxConstants.SYNTAX_STYLE_MARKDOWN),
            Map.entry("csv", SyntaxConstants.SYNTAX_STYLE_CSV),
            Map.entry("asm", SyntaxConstants.SYNTAX_STYLE_ASSEMBLER_X86),
            Map.entry("dockerfile", SyntaxConstants.SYNTAX_STYLE_DOCKERFILE));

    private SyntaxStyles() {
    }

    /** Der RSyntaxTextArea-Stil zu einer Endung (ohne Punkt, beliebige Schreibweise). */
    public static String forExtension(String extension) {
        if (extension == null) {
            return SyntaxConstants.SYNTAX_STYLE_NONE;
        }
        return BY_EXTENSION.getOrDefault(extension.toLowerCase(java.util.Locale.ROOT),
                SyntaxConstants.SYNTAX_STYLE_NONE);
    }
}
