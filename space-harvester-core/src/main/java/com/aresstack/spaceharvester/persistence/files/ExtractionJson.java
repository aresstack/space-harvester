package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.assets.Block;
import com.aresstack.spaceharvester.application.assets.DocumentHead;
import com.aresstack.spaceharvester.application.assets.EmbeddedEntry;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.Link;
import com.aresstack.spaceharvester.application.assets.Location;
import com.aresstack.spaceharvester.application.assets.Outcome;
import com.aresstack.spaceharvester.application.assets.PartId;
import com.aresstack.spaceharvester.application.assets.ReadExtent;
import com.aresstack.spaceharvester.application.assets.StatusReason;
import com.aresstack.spaceharvester.application.assets.StructuredDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON-Form der strukturierten Extraktion für {@link FileSystemExtractionStore}: Blöcke als je eine Zeile, Kopfdaten,
 * Status und Verzeichnis in {@code document.json}. Optionale Felder fehlen, statt {@code null} zu tragen. Unbekannte
 * Status- oder Grundcodes machen die Ablage unlesbar (sie gilt dann als nicht vorhanden und wird neu gelesen).
 */
final class ExtractionJson {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private ExtractionJson() {
    }

    static ObjectNode block(Block block) {
        ObjectNode node = JSON.createObjectNode();
        node.put("index", block.index());
        switch (block) {
            case Block.Heading heading -> {
                node.put("type", "heading");
                node.put("level", heading.level());
                node.put("text", heading.text());
            }
            case Block.Paragraph paragraph -> {
                node.put("type", "paragraph");
                node.put("text", paragraph.text());
            }
            case Block.ListItem item -> {
                node.put("type", "list-item");
                node.put("ordered", item.ordered());
                node.put("level", item.level());
                node.put("text", item.text());
            }
            case Block.Code code -> {
                node.put("type", "code");
                node.put("text", code.text());
                code.language().ifPresent(language -> node.put("language", language));
            }
            case Block.Table table -> {
                node.put("type", "table");
                if (table.continued()) {
                    node.put("continued", true);
                }
                ArrayNode cells = node.putArray("cells");
                for (Block.Cell cell : table.cells()) {
                    ObjectNode c = cells.addObject();
                    c.put("row", cell.row());
                    c.put("column", cell.column());
                    c.put("text", cell.text());
                    if (cell.header()) {
                        c.put("header", true);
                    }
                    cell.cellRef().ifPresent(ref -> c.put("cellRef", ref));
                }
            }
        }
        block.location().ifPresent(location -> {
            ObjectNode l = node.putObject("location");
            location.page().ifPresent(page -> l.put("page", page));
            location.slide().ifPresent(slide -> l.put("slide", slide));
            location.sheet().ifPresent(sheet -> l.put("sheet", sheet));
        });
        if (!block.links().isEmpty()) {
            ArrayNode links = node.putArray("links");
            for (Link link : block.links()) {
                ObjectNode l = links.addObject();
                l.put("target", link.target());
                link.label().ifPresent(label -> l.put("label", label));
            }
        }
        return node;
    }

    static Block block(JsonNode node) {
        long index = node.path("index").asLong(-1);
        Optional<Location> location = Optional.empty();
        if (node.has("location")) {
            JsonNode l = node.get("location");
            location = Optional.of(new Location(optionalInt(l, "page"), optionalInt(l, "slide"),
                    optionalText(l, "sheet")));
        }
        List<Link> links = new ArrayList<>();
        for (JsonNode l : node.path("links")) {
            links.add(new Link(l.path("target").asString(), optionalText(l, "label")));
        }
        String text = node.path("text").asString("");
        return switch (node.path("type").asString("")) {
            case "heading" -> new Block.Heading(index, location, links, node.path("level").asInt(), text);
            case "paragraph" -> new Block.Paragraph(index, location, links, text);
            case "list-item" -> new Block.ListItem(index, location, links, node.path("ordered").asBoolean(),
                    node.path("level").asInt(), text);
            case "code" -> new Block.Code(index, location, links, text, optionalText(node, "language"));
            case "table" -> {
                List<Block.Cell> cells = new ArrayList<>();
                for (JsonNode c : node.path("cells")) {
                    cells.add(new Block.Cell(c.path("row").asLong(), c.path("column").asInt(),
                            c.path("text").asString(""), c.path("header").asBoolean(false),
                            optionalText(c, "cellRef")));
                }
                yield new Block.Table(index, location, links, cells, node.path("continued").asBoolean(false));
            }
            default -> throw new IllegalArgumentException("Unbekannter Blocktyp");
        };
    }

    static ObjectNode document(StructuredDocument document) {
        ObjectNode node = JSON.createObjectNode();
        DocumentHead head = document.head();
        ObjectNode h = node.putObject("head");
        head.title().ifPresent(value -> h.put("title", value));
        head.subject().ifPresent(value -> h.put("subject", value));
        head.from().ifPresent(value -> h.put("from", value));
        if (!head.to().isEmpty()) {
            ArrayNode to = h.putArray("to");
            head.to().forEach(to::add);
        }
        head.date().ifPresent(value -> h.put("date", value.toString()));
        node.set("outcome", outcome(document.outcome()));
        ArrayNode directory = node.putArray("directory");
        for (EmbeddedEntry entry : document.directory()) {
            ObjectNode e = directory.addObject();
            e.put("id", entry.id().value());
            entry.sha256().ifPresent(value -> e.put("sha256", value));
            entry.displayName().ifPresent(value -> e.put("displayName", value));
            entry.mediaType().ifPresent(value -> e.put("mediaType", value));
            e.put("position", entry.position());
            entry.parent().ifPresent(value -> e.put("parent", value.value()));
            e.put("depth", entry.depth());
            entry.size().ifPresent(value -> e.put("size", value));
            e.set("outcome", outcome(entry.outcome()));
        }
        node.put("blocks", document.blocks());
        node.put("chars", document.chars());
        return node;
    }

    static StructuredDocument document(JsonNode node) {
        JsonNode h = node.path("head");
        List<String> to = new ArrayList<>();
        for (JsonNode value : h.path("to")) {
            to.add(value.asString());
        }
        DocumentHead head = new DocumentHead(optionalText(h, "title"), optionalText(h, "subject"),
                optionalText(h, "from"), to, optionalText(h, "date").map(Instant::parse));
        List<EmbeddedEntry> directory = new ArrayList<>();
        for (JsonNode e : node.path("directory")) {
            directory.add(new EmbeddedEntry(PartId.parse(e.path("id").asString()), optionalText(e, "sha256"),
                    optionalText(e, "displayName"), optionalText(e, "mediaType"), e.path("position").asString(),
                    optionalText(e, "parent").map(PartId::parse), e.path("depth").asInt(),
                    e.has("size") ? Optional.of(e.get("size").asLong()) : Optional.empty(),
                    outcome(e.path("outcome"))));
        }
        return new StructuredDocument(head, outcome(node.path("outcome")), directory, node.path("blocks").asLong(),
                node.path("chars").asLong());
    }

    private static ObjectNode outcome(Outcome outcome) {
        ObjectNode node = JSON.createObjectNode();
        node.put("status", outcome.status().name());
        outcome.reason().ifPresent(reason -> node.put("reason", reason.code()));
        if (outcome.truncated()) {
            node.put("truncated", true);
        }
        outcome.extent().ifPresent(extent -> {
            node.put("bytesRead", extent.bytes());
            node.put("charsRead", extent.chars());
        });
        return node;
    }

    private static Outcome outcome(JsonNode node) {
        ExtractionStatus status = ExtractionStatus.valueOf(node.path("status").asString(""));
        Optional<StatusReason> reason = Optional.empty();
        if (node.has("reason")) {
            reason = Optional.of(StatusReason.fromCode(node.get("reason").asString())
                    .orElseThrow(() -> new IllegalArgumentException("Unbekannter Grund")));
        }
        Optional<ReadExtent> extent = node.has("bytesRead")
                ? Optional.of(new ReadExtent(node.get("bytesRead").asLong(), node.path("charsRead").asLong()))
                : Optional.empty();
        return new Outcome(status, reason, node.path("truncated").asBoolean(false), extent);
    }

    private static Optional<Integer> optionalInt(JsonNode node, String field) {
        return node.hasNonNull(field) ? Optional.of(node.get(field).asInt()) : Optional.empty();
    }

    private static Optional<String> optionalText(JsonNode node, String field) {
        return node.hasNonNull(field) ? Optional.of(node.get(field).asString()) : Optional.empty();
    }
}
