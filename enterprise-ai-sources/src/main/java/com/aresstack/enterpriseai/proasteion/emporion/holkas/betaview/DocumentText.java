package com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview;

import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractedBlock;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractedDocument;

/** Extrahierter Inhalt als Klartext mit leichtgewichtigem Markdown (wie die Dateiquelle). */
final class DocumentText {

    private DocumentText() {
    }

    static String of(String fileName, ExtractedDocument document) {
        StringBuilder sb = new StringBuilder();
        String title = document.title() != null && !document.title().trim().isEmpty() ? document.title().trim() : fileName;
        sb.append("# ").append(title);
        for (ExtractedBlock block : document.blocks()) {
            String text = block.text();
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            sb.append("\n\n");
            switch (block.kind()) {
                case HEADING:
                    sb.append(hashes(block.attributes().get("level"))).append(' ').append(text.trim());
                    break;
                case CODE:
                    String language = block.attributes().get("language");
                    sb.append("```").append(language == null ? "" : language).append('\n').append(text)
                            .append("\n```");
                    break;
                default:
                    sb.append(text.trim());
                    break;
            }
        }
        return sb.toString();
    }

    private static String hashes(String level) {
        int n;
        try {
            n = level == null ? 2 : Math.max(1, Math.min(6, Integer.parseInt(level.trim())));
        } catch (NumberFormatException e) {
            n = 2;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append('#');
        }
        return sb.toString();
    }
}
