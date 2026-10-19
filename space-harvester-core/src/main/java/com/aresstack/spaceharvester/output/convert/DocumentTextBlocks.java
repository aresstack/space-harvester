package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Block;
import com.aresstack.spaceharvester.output.convert.StorageDocument.CodeBlock;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Heading;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Inline;
import com.aresstack.spaceharvester.output.convert.StorageDocument.LineBreak;
import com.aresstack.spaceharvester.output.convert.StorageDocument.ListBlock;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Paragraph;
import com.aresstack.spaceharvester.output.convert.StorageDocument.Text;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blöcke aus dem Lesetext einer Quelle ohne natives Format ({@link NativePayload#DOCUMENT_TEXT}, #187). Der Text folgt
 * dem Vertrag von {@code KnowledgeDocument} im Enterprise AI Client: Überschriften als {@code #}-Zeilen, Absätze durch
 * Leerzeilen getrennt, Listenpunkte als {@code - }/{@code 1. }-Zeilen, Code in {@code ```}-Blöcken. Alles Übrige bleibt
 * Text, ohne Auszeichnung zu deuten.
 */
final class DocumentTextBlocks {

    private static final Pattern HEADING = Pattern.compile("(#{1,6})\\s+(.*)");
    private static final Pattern BULLET = Pattern.compile("[-*]\\s+(.*)");
    private static final Pattern NUMBERED = Pattern.compile("\\d{1,9}[.)]\\s+(.*)");

    private DocumentTextBlocks() {
    }

    /** @return leer, wenn die Payload kein solcher Lesetext ist */
    static Optional<List<Block>> of(NativePayload payload) {
        if (!NativePayload.DOCUMENT_TEXT.equals(payload.nativeFormat())) {
            return Optional.empty();
        }
        return Optional.of(parse(new String(payload.bytes(), StandardCharsets.UTF_8)));
    }

    static List<Block> parse(String text) {
        List<Block> blocks = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        List<List<Block>> items = new ArrayList<>();
        boolean ordered = false;
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                flush(blocks, paragraph, items, ordered);
                StringBuilder code = new StringBuilder();
                int end = index + 1;
                while (end < lines.length && !lines[end].strip().startsWith("```")) {
                    code.append(code.isEmpty() ? "" : "\n").append(lines[end]);
                    end++;
                }
                blocks.add(new CodeBlock(trimmed.substring(3).strip(), code.toString()));
                index = end;
                continue;
            }
            Matcher heading = HEADING.matcher(trimmed);
            Matcher bullet = BULLET.matcher(trimmed);
            Matcher numbered = NUMBERED.matcher(trimmed);
            if (trimmed.isEmpty()) {
                flush(blocks, paragraph, items, ordered);
            } else if (heading.matches()) {
                flush(blocks, paragraph, items, ordered);
                blocks.add(new Heading(heading.group(1).length(), List.of(text(heading.group(2)))));
            } else if (bullet.matches() || numbered.matches()) {
                boolean number = !bullet.matches();
                if (!paragraph.isEmpty() || (!items.isEmpty() && ordered != number)) {
                    flush(blocks, paragraph, items, ordered);
                }
                ordered = number;
                items.add(List.of(new Paragraph(List.of(text((number ? numbered : bullet).group(1))))));
            } else {
                if (!items.isEmpty()) {
                    flush(blocks, paragraph, items, ordered);
                }
                paragraph.add(trimmed);
            }
        }
        flush(blocks, paragraph, items, ordered);
        return blocks;
    }

    private static void flush(List<Block> blocks, List<String> paragraph, List<List<Block>> items, boolean ordered) {
        if (!paragraph.isEmpty()) {
            List<Inline> content = new ArrayList<>();
            for (String line : paragraph) {
                if (!content.isEmpty()) {
                    content.add(new LineBreak());
                }
                content.add(text(line));
            }
            blocks.add(new Paragraph(content));
            paragraph.clear();
        }
        if (!items.isEmpty()) {
            blocks.add(new ListBlock(ordered, List.copyOf(items), null));
            items.clear();
        }
    }

    private static Text text(String value) {
        return Text.plain(value);
    }
}
