package com.aresstack.spaceharvester.application.assets;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Project verified OneNote page locations into a read-only preview. */
public record OneNotePreview(List<Section> sections, Outcome outcome) {

    public OneNotePreview {
        sections = List.copyOf(sections);
        Objects.requireNonNull(outcome, "outcome");
    }

    public record Section(Optional<NotePageLocation> location, String text) {
        public Section {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(text, "text");
        }

        public String label() {
            return location.map(page -> "Seite " + page.index()).orElse("Ohne Seitenzuordnung");
        }
    }

    public boolean hasPageBoundaries() {
        return sections.stream().anyMatch(section -> section.location().isPresent());
    }

    public static OneNotePreview fromBlocks(List<Block> blocks, Outcome outcome) {
        List<Section> sections = new ArrayList<>();
        Optional<NotePageLocation> current = Optional.empty();
        StringBuilder text = new StringBuilder();
        for (Block block : blocks) {
            Optional<NotePageLocation> location = block.location().flatMap(Location::notePage);
            if (!location.equals(current)) {
                appendSection(sections, current, text);
                current = location;
            }
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(blockText(block));
        }
        appendSection(sections, current, text);
        return new OneNotePreview(sections, outcome);
    }

    private static void appendSection(List<Section> sections, Optional<NotePageLocation> location,
                                      StringBuilder text) {
        if (!text.isEmpty()) {
            sections.add(new Section(location, text.toString()));
            text.setLength(0);
        }
    }

    private static String blockText(Block block) {
        return switch (block) {
            case Block.Heading heading -> heading.text();
            case Block.Paragraph paragraph -> paragraph.text();
            case Block.ListItem item -> "  ".repeat(item.level() - 1) + "- " + item.text();
            case Block.Code code -> code.text();
            case Block.Table table -> tableText(table);
        };
    }

    private static String tableText(Block.Table table) {
        StringBuilder text = new StringBuilder();
        long previousRow = -1;
        for (Block.Cell cell : table.cells()) {
            if (!text.isEmpty()) {
                text.append(previousRow == cell.row() ? '\t' : '\n');
            }
            text.append(cell.text());
            previousRow = cell.row();
        }
        return text.toString();
    }
}
