package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.document.DocxExportWriter;
import com.aresstack.spaceharvester.document.ExportDocumentNode;
import com.aresstack.spaceharvester.document.MarkdownExportWriter;
import com.aresstack.spaceharvester.document.PdfExportWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Build one immutable export document for all supported writers. */
public final class ChatExport {
    public enum Format {
        MARKDOWN("Markdown", "md"), PDF("PDF", "pdf"), WORD("Word", "docx");
        public final String label;
        public final String extension;
        Format(String label, String extension) {
            this.label = label;
            this.extension = extension;
        }
    }

    public enum Scope { CHAT, ANSWER, SOURCES }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private ChatExport() { }

    public static String displayTitle(ChatRecord chat) {
        return chat.renamed() ? chat.title() : "Neuer Chat";
    }

    public static String subject(ChatRecord chat) {
        if (chat.renamed()) {
            return chat.title();
        }
        return chat.messages().stream().filter(ChatRecord.Message::user)
                .map(ChatRecord.Message::text).filter(text -> !text.isBlank()).findFirst().orElse("Chat");
    }

    public static String filename(ChatRecord chat, int answerIndex, Scope scope, Format format) {
        String name = subject(chat).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").strip().replaceAll("[. ]+$", "");
        if (name.isBlank()) {
            name = "Chat";
        }
        if (name.length() > 90) {
            name = name.substring(0, 90).strip().replaceAll("[. ]+$", "");
        }
        String suffix = "_" + DATE.format(Instant.ofEpochMilli(chat.createdAt()));
        if (answerIndex > 0) {
            suffix += "_Antwort-" + answerIndex;
        }
        if (scope == Scope.SOURCES) {
            suffix += "_Quellen";
        }
        return name + suffix + "." + format.extension;
    }

    public static List<ChatRecord.Source> sources(List<ChatRecord.Message> messages) {
        Map<List<String>, ChatRecord.Source> unique = new LinkedHashMap<>();
        int anonymous = 0;
        for (ChatRecord.Message message : messages) {
            for (ChatRecord.Source source : message.sources()) {
                List<String> key;
                if (source.pageId() != null && !source.pageId().isBlank()) {
                    key = List.of("page", source.sourceId(), source.pageId());
                } else if (source.location() != null && !source.location().isBlank()) {
                    key = List.of("location", source.sourceId(), source.location());
                } else {
                    key = List.of("anonymous", Integer.toString(anonymous++));
                }
                unique.putIfAbsent(key, source);
            }
        }
        return List.copyOf(unique.values());
    }

    public static ExportDocumentNode document(ChatRecord chat, ChatRecord.Message answer, Scope scope) {
        List<ChatRecord.Message> messages = answer == null ? chat.messages() : List.of(answer);
        if (scope == Scope.SOURCES) {
            return new ExportDocumentNode(subject(chat) + " – Quellen", sourceList(sources(messages)), List.of());
        }
        List<ExportDocumentNode> chapters = new ArrayList<>();
        for (ChatRecord.Message message : messages) {
            String title = (message.user() ? "Du" : "Assistent") + " · "
                    + TIME.format(Instant.ofEpochMilli(message.at()));
            String text = (message.area() == null ? "" : "Bereich: " + message.area() + "\n\n")
                    + message.text() + (message.cancelled() ? "\n\n*Abgebrochen*" : "");
            if (!message.sources().isEmpty()) {
                text += "\n\n**Quellen**\n\n" + sourceList(message.sources());
            }
            chapters.add(new ExportDocumentNode(title, text, List.of()));
        }
        return new ExportDocumentNode(subject(chat), null, chapters);
    }

    private static String sourceList(List<ChatRecord.Source> sources) {
        StringBuilder text = new StringBuilder();
        int number = 0;
        for (ChatRecord.Source source : sources) {
            text.append(++number).append(". ").append(plain(source.title()))
                    .append(" — ").append(plain(source.sourceId()));
            if (source.pageId() != null) {
                text.append(" / ").append(plain(source.pageId()));
            }
            if (source.location() != null) {
                text.append(" — ").append(plain(safeAddress(source.location())));
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static String safeAddress(String location) {
        try {
            java.net.URI uri = java.net.URI.create(location);
            if (uri.getRawUserInfo() != null) {
                return location.replace(uri.getRawUserInfo() + "@", "");
            }
        } catch (IllegalArgumentException ignored) {
            // Preserve non-URI source references.
        }
        return location;
    }

    private static String plain(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ");
    }

    public static void write(ExportDocumentNode document, Format format, Path target) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), ".chat-export-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                switch (format) {
                    case MARKDOWN -> new MarkdownExportWriter().write(document, output);
                    case PDF -> new PdfExportWriter().write(document, output);
                    case WORD -> new DocxExportWriter().write(document, output);
                }
            }
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
