package com.aresstack.spaceharvester.parity;

import com.aresstack.spaceharvester.parity.DocumentOutline.Heading;
import com.aresstack.spaceharvester.parity.DocumentOutline.Table;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest ein Markdown (GFM) in das neutrale Modell. Eine Zeile, die nur aus Fettschrift besteht, zählt als Überschrift
 * der Ebene 7: So schreibt der Enterprise AI Client Kapitel unterhalb von Ebene 6.
 */
public final class MarkdownReader {

    private static final Pattern ATX = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BOLD_LINE = Pattern.compile("^\\*\\*([^*].*?)\\*\\*$");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(\\s*<?([^)\\s>]+)>?[^)]*\\)");
    private static final Pattern LINK = Pattern.compile("(?<!!)\\[[^\\]]*]\\(\\s*<?([^)\\s>]+)>?[^)]*\\)");
    private static final Pattern AUTOLINK = Pattern.compile("<((?:https?://|mailto:)[^>\\s]+)>");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");
    private static final Pattern TOC_ENTRY = Pattern.compile("^\\s*[-*+]\\s+\\[[^\\]]+]\\(#[^)]+\\)\\s*$");
    private static final int TOC_WINDOW = 40;

    private MarkdownReader() {
    }

    public static DocumentOutline read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8), file.getParent());
    }

    static DocumentOutline parse(String markdown, Path directory) {
        String[] lines = markdown.split("\r?\n", -1);
        List<Heading> headings = new ArrayList<>();
        List<Table> tables = new ArrayList<>();
        Set<String> external = new LinkedHashSet<>();
        StringBuilder text = new StringBuilder();
        int internal = 0;
        int images = 0;
        int notes = 0;
        int mermaid = 0;
        int tocEntries = 0;
        boolean fence = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String trimmed = line.strip();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                if (!fence && trimmed.substring(3).strip().toLowerCase().startsWith("mermaid")) {
                    mermaid++;
                }
                fence = !fence;
                continue;
            }
            if (fence) {
                text.append(line).append('\n');
                continue;
            }
            Matcher atx = ATX.matcher(trimmed);
            Matcher bold = BOLD_LINE.matcher(trimmed);
            if (atx.matches()) {
                headings.add(new Heading(atx.group(1).length(), inline(atx.group(2))));
            } else if (bold.matches()) {
                headings.add(new Heading(7, inline(bold.group(1))));
            }
            if (index < TOC_WINDOW && TOC_ENTRY.matcher(line).matches()) {
                tocEntries++;
            }
            if (index + 1 < lines.length && trimmed.contains("|") && TABLE_SEPARATOR.matcher(lines[index + 1]).matches()) {
                int columns = cells(trimmed);
                int rows = 1;
                int next = index + 2;
                while (next < lines.length && lines[next].contains("|") && !lines[next].isBlank()) {
                    rows++;
                    next++;
                }
                tables.add(new Table(rows, columns, true));
            }
            Matcher image = IMAGE.matcher(line);
            while (image.find()) {
                String target = image.group(2);
                if (target.regionMatches(true, 0, "data:image", 0, 10) || local(target, directory)) {
                    images++;
                } else {
                    notes++;
                }
            }
            Matcher link = LINK.matcher(line);
            while (link.find()) {
                String target = link.group(1);
                if (target.startsWith("#")) {
                    internal++;
                } else if (target.startsWith("http://") || target.startsWith("https://")
                        || target.startsWith("mailto:")) {
                    external.add(target);
                }
            }
            Matcher autolink = AUTOLINK.matcher(line);
            while (autolink.find()) {
                external.add(autolink.group(1));
            }
            text.append(inline(IMAGE.matcher(line).replaceAll(""))).append('\n');
        }
        String plain = text.toString();
        notes += Patterns.count(Patterns.IMAGE_NOTE, plain.replace("(attachment:", ""));
        return new DocumentOutline("md", headings, tocEntries >= 2, false, false, 0, images, notes, tables,
                external, internal, mermaid, plain);
    }

    /** Ein lokales Bild zählt als eingebettet, wenn die Datei neben dem Markdown liegt. */
    private static boolean local(String target, Path directory) {
        if (directory == null || target.contains(":") || target.startsWith("/")) {
            return false;
        }
        try {
            return Files.isRegularFile(directory.resolve(target));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int cells(String row) {
        String inner = row.strip();
        if (inner.startsWith("|")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("|")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        return inner.split("(?<!\\\\)\\|", -1).length;
    }

    /** Markdown-Auszeichnung aus einer Zeile entfernen: Links behalten den Text, Hervorhebungen fallen weg. */
    private static String inline(String line) {
        return line.replaceAll("(?<!!)\\[([^\\]]*)]\\([^)]*\\)", "$1")
                .replaceAll("\\{#[^}]*}", "")
                .replaceAll("<a\\s+(?:id|name)=\"[^\"]*\"\\s*>\\s*</a>", "")
                .replaceAll("[*_~`]+", "")
                .replaceAll("^#{1,6}\\s+", "")
                .replaceAll("^\\s*[-+]\\s+|^\\s*\\d+\\.\\s+|^\\s*>\\s?", "")
                .strip();
    }
}
