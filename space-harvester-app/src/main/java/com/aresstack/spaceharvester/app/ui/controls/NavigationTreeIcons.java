package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.enterpriseai.ui.comic.paint.SidebarIcons;
import com.aresstack.spaceharvester.app.ui.Glyph;
import javax.swing.Icon;
import java.util.Map;

/** Reuse the Explorer's icons and translate legacy label decorations at the UI boundary. */
public final class NavigationTreeIcons {
    public static final Icon FOLDER = SidebarIcons.folder();
    public static final Icon PAGE = SidebarIcons.page();
    public static final Icon OUTGOING = SidebarIcons.linksOutgoing();
    public static final Icon INCOMING = SidebarIcons.linksIncoming();
    public static final Icon STRUCTURE = SidebarIcons.pageTree();

    private static final Map<String, Icon> LEGACY = Map.ofEntries(
            Map.entry("📂", FOLDER), Map.entry("📁", FOLDER), Map.entry("📦", FOLDER),
            Map.entry("📋", STRUCTURE), Map.entry("📄", PAGE), Map.entry("📝", PAGE),
            Map.entry("📞", OUTGOING), Map.entry("🚀", OUTGOING), Map.entry("▶", OUTGOING),
            Map.entry("🔄", Glyph.REFRESH), Map.entry("📎", INCOMING), Map.entry("🔗", INCOMING),
            Map.entry("📚", FOLDER), Map.entry("📖", PAGE), Map.entry("🌿", PAGE),
            Map.entry("🔢", Glyph.TABLE), Map.entry("📊", Glyph.TABLE), Map.entry("🗄", Glyph.TABLE),
            Map.entry("🗃", Glyph.TABLE), Map.entry("💾", Glyph.TABLE), Map.entry("🖥", PAGE),
            Map.entry("⚙", STRUCTURE), Map.entry("⏳", Glyph.SYNC), Map.entry("⚠", Glyph.WARNING),
            Map.entry("•", PAGE));

    private NavigationTreeIcons() { }

    public static String plainLabel(String text) {
        String prefix = prefix(text);
        return prefix == null ? text : text.substring(prefix.length()).stripLeading();
    }

    public static Icon legacyIcon(String text, Icon fallback) {
        String prefix = prefix(text);
        return prefix == null ? fallback : LEGACY.get(prefix);
    }

    private static String prefix(String text) {
        if (text == null) {
            return null;
        }
        for (String symbol : LEGACY.keySet()) {
            if (text.startsWith(symbol + " ")) {
                return symbol;
            }
        }
        return null;
    }

    public static Icon relation(String kind) {
        if (kind == null) {
            return OUTGOING;
        }
        return switch (kind) {
            case "PERFORM" -> Glyph.REFRESH;
            case "COPY", "INCLUDE", "USING", "JCL_DEP_INCLUDE", "JCL_DEP_JCLLIB" -> INCOMING;
            case "VIEW", "DB_ACCESS", "JCL_DEP_DSN", "DEPENDENCY_VIEW", "DEPENDENCY_DB_ACCESS" -> Glyph.TABLE;
            case "INPUT_MAP" -> PAGE;
            default -> OUTGOING;
        };
    }
}
