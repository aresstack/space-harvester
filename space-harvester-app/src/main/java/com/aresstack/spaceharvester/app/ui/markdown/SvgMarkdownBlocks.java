package com.aresstack.spaceharvester.app.ui.markdown;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fasst nacktes SVG in einer Modellantwort (eine Zeile, die außerhalb eines Codezauns mit {@code <svg} beginnt, bis
 * zur Zeile mit {@code </svg>}) in einen {@code ```svg}-Zaun. CommonMark beendet einen HTML-Block an der ersten
 * Leerzeile und erkennt mehrzeilige Start-Tags nicht; ohne diesen Schritt zerfiele ein SVG in Absätze aus Rohtext.
 * Noch offenes SVG (Streaming) wird ebenfalls gezäunt und erscheint so bis zum Ende als Codeblock.
 */
final class SvgMarkdownBlocks {

    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$");
    private static final Pattern SVG_START = Pattern.compile("^ {0,3}(<\\?xml[^>]*\\?>\\s*)?<svg(\\s|>|$).*",
            Pattern.CASE_INSENSITIVE);

    private SvgMarkdownBlocks() {
    }

    static String fence(String markdown) {
        if (markdown == null || markdown.toLowerCase(java.util.Locale.ROOT).indexOf("<svg") < 0) {
            return markdown;
        }
        String[] lines = markdown.split("\n", -1);
        StringBuilder out = new StringBuilder(markdown.length() + 32);
        String openFence = null;
        boolean inSvg = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (inSvg) {
                out.append(line).append('\n');
                if (line.toLowerCase(java.util.Locale.ROOT).contains("</svg")) {
                    out.append("````\n");
                    inSvg = false;
                }
                continue;
            }
            Matcher fence = FENCE.matcher(line);
            if (openFence != null) {
                if (fence.matches() && line.trim().startsWith(openFence) && line.trim().replace(
                        openFence.substring(0, 1), "").isEmpty()) {
                    openFence = null;
                }
            } else if (fence.matches()) {
                openFence = fence.group(1);
            } else if (SVG_START.matcher(line).matches()) {
                out.append("````svg\n").append(line).append('\n');
                if (line.toLowerCase(java.util.Locale.ROOT).contains("</svg")) {
                    out.append("````\n");
                } else {
                    inSvg = true;
                }
                continue;
            }
            out.append(line);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }
}
