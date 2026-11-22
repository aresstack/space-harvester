package com.aresstack.spaceharvester.app.ui.deck.editor;

import java.util.Map;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

/** Welche Syntaxhervorhebung zu welcher Dateiendung gehört; Unbekanntes bleibt einfacher Text. */
public final class SyntaxStyles {

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
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
