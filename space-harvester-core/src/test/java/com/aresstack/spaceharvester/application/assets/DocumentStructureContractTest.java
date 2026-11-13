package com.aresstack.spaceharvester.application.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Vertrag der strukturierten Extraktion (ADR 0026, Stufe 1): nur Typen, kein Verhalten. */
class DocumentStructureContractTest {

    private static final String A = "a".repeat(64);
    private static final String B = "B".repeat(64);
    private static final PageKey PAGE = new PageKey("wiki", "4711");

    @Test
    void partIdsRoundTripAsTextWithOwnPrefixes() {
        PartId content = new PartId.Content(A, 2);
        PartId position = new PartId.Position(B, "/1/3");

        assertThat(content.value()).isEqualTo("sha256:" + A + ":2");
        assertThat(position.value()).isEqualTo("pos:" + "b".repeat(64) + ":/1/3");
        assertThat(PartId.parse(content.value())).isEqualTo(content);
        assertThat(PartId.parse(position.value())).isEqualTo(position);
        assertThat(((PartId.Position) position).depth()).isEqualTo(2);
    }

    @Test
    void partIdsRejectNamesPathsAndBrokenValues() {
        assertThatThrownBy(() -> PartId.parse("/notiz.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PartId.parse("sha256:" + A + ":0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PartId.parse("sha256:" + A + ":x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PartId.parse("sha256:abc:1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartId.Position(A, "/ordner/unter.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartId.Position(A, "/0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartId.Position(A, "../1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameContentTwiceGivesTwoIdsAndOneHash() {
        PartId first = new PartId.Content(A, 1);
        PartId second = new PartId.Content(A, 2);

        assertThat(first).isNotEqualTo(second);
        assertThat(((PartId.Content) first).sha256()).isEqualTo(((PartId.Content) second).sha256());
    }

    @Test
    void documentKeysSeparatePageAttachmentAndEmbeddedFile() {
        DocumentKey page = DocumentKey.page(PAGE);
        DocumentKey attachment = DocumentKey.attachment(PAGE, "att123");
        DocumentKey embedded = attachment.child(new PartId.Content(A, 1));

        assertThat(page.isPage()).isTrue();
        assertThat(attachment.isPage()).isFalse();
        assertThat(attachment.isEmbedded()).isFalse();
        assertThat(embedded.isEmbedded()).isTrue();
        assertThat(embedded.attachmentId()).contains("att123");
        assertThat(page.child(new PartId.Position(A, "/1")).attachmentId()).isEmpty();
        assertThatThrownBy(() -> embedded.child(new PartId.Content(A, 2))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DocumentKey.attachment(PAGE, " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outcomeKeepsStatusAsDataWithReasonAndExtent() {
        Outcome ok = Outcome.ok(new ReadExtent(10, 5));
        Outcome cut = Outcome.truncated(StatusReason.UNPACKED_TOO_LARGE, new ReadExtent(20, 7));

        assertThat(ok.status()).isEqualTo(ExtractionStatus.OK);
        assertThat(ok.reason()).isEmpty();
        assertThat(cut.status()).isEqualTo(ExtractionStatus.PARTIAL);
        assertThat(cut.truncated()).isTrue();
        assertThat(cut.extent()).contains(new ReadExtent(20, 7));
        assertThat(Outcome.of(ExtractionStatus.FAILED, StatusReason.ENCRYPTED).reason())
                .contains(StatusReason.ENCRYPTED);

        assertThatThrownBy(() -> new Outcome(ExtractionStatus.PARTIAL, Optional.of(StatusReason.TIMEOUT), true,
                Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Outcome(ExtractionStatus.FAILED, Optional.empty(), false, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Outcome(ExtractionStatus.OK, Optional.of(StatusReason.EMPTY), false,
                Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadExtent(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reasonsHaveFixedCodes() {
        assertThat(StatusReason.UNPACKED_TOO_LARGE.code()).isEqualTo("unpacked-too-large");
        assertThat(StatusReason.MEDIA_TYPE_EXCLUDED.code()).isEqualTo("media-type-excluded");
        for (StatusReason reason : StatusReason.values()) {
            assertThat(StatusReason.fromCode(reason.code())).contains(reason);
        }
        assertThat(StatusReason.TEXT_RATIO_EXCEEDED.code()).isEqualTo("text-ratio-exceeded");
        assertThat(StatusReason.TEMP_SPACE_LOW.code()).isEqualTo("temp-space-low");
        assertThat(StatusReason.ENTRIES_NOT_READABLE.code()).isEqualTo("entries-not-readable");
        assertThat(StatusReason.fromCode("Anhang nicht lesbar")).isEmpty();
    }

    @Test
    void locationsAreOnlySetWhenRead() {
        assertThat(Location.page(3).page()).contains(3);
        assertThat(Location.sheet("Kosten").sheet()).contains("Kosten");
        assertThatThrownBy(() -> new Location(Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Location.slide(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blocksCarryTypedFieldsAndLinksApartFromText() {
        Link link = new Link("https://example.org", Optional.of("Beispiel"));
        Block heading = new Block.Heading(0, Optional.empty(), List.of(), 2, "Einleitung");
        Block paragraph = new Block.Paragraph(1, Optional.of(Location.page(1)), List.of(link), "Siehe Beispiel");
        Block item = new Block.ListItem(2, Optional.empty(), null, true, 1, "Erstens");
        Block table = new Block.Table(3, Optional.of(Location.sheet("Blatt1")), List.of(),
                List.of(new Block.Cell(0, 0, "Kopf", true, Optional.empty()),
                        new Block.Cell(1, 0, "Wert", false, Optional.of("D17"))), false);
        Block code = new Block.Code(4, Optional.empty(), List.of(), "x = 1", Optional.of(""));

        assertThat(paragraph.links()).containsExactly(link);
        assertThat(((Block.Paragraph) paragraph).text()).doesNotContain("https://");
        assertThat(item.links()).isEmpty();
        assertThat(((Block.Table) table).cells().get(1).cellRef()).contains("D17");
        assertThat(((Block.Code) code).language()).isEmpty();
        assertThat(List.of(heading, paragraph, item, table, code)).extracting(Block::index)
                .containsExactly(0L, 1L, 2L, 3L, 4L);

        assertThatThrownBy(() -> new Block.Heading(0, Optional.empty(), List.of(), 7, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Block.ListItem(0, Optional.empty(), List.of(), false, 0, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Block.Paragraph(-1, Optional.empty(), List.of(), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Block.Cell(0, -1, "x", false, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void directoryEntriesKeepIdHashNameAndPositionApart() {
        EmbeddedEntry mail = new EmbeddedEntry(new PartId.Content(A, 1), Optional.of(A), Optional.of("weiter.eml"),
                Optional.of("message/rfc822"), "/1", Optional.empty(), 1, Optional.of(100L),
                Outcome.ok(new ReadExtent(100, 40)));
        EmbeddedEntry broken = new EmbeddedEntry(new PartId.Position(B, "/1/2"), Optional.empty(),
                Optional.of("../../etc/notiz.txt"), Optional.empty(), "/1/2", Optional.of(mail.id()), 2,
                Optional.empty(), Outcome.of(ExtractionStatus.FAILED, StatusReason.CORRUPT));

        StructuredDocument document = new StructuredDocument(null, Outcome.ok(new ReadExtent(500, 60)),
                List.of(mail, broken), 3, 60);

        assertThat(document.head()).isEqualTo(DocumentHead.EMPTY);
        assertThat(document.directory()).containsExactly(mail, broken);
        assertThat(broken.id().value()).doesNotContain("notiz");

        assertThatThrownBy(() -> new EmbeddedEntry(new PartId.Content(A, 1), Optional.of(B), Optional.empty(),
                Optional.empty(), "/1", Optional.empty(), 1, Optional.empty(), mail.outcome()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddedEntry(new PartId.Position(B, "/1/2"), Optional.empty(),
                Optional.empty(), Optional.empty(), "/1/3", Optional.of(mail.id()), 2, Optional.empty(),
                broken.outcome())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddedEntry(new PartId.Position(B, "/1/2"), Optional.empty(),
                Optional.empty(), Optional.empty(), "/1/2", Optional.empty(), 2, Optional.empty(),
                broken.outcome())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredDocument(null, mail.outcome(), List.of(mail, mail), 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identicalPartsGetOrdinalsOneAndTwoAcrossTheWholeContainer() {
        PartIdNumbering numbering = new PartIdNumbering(B);

        PartId.Content first = numbering.content(A);
        PartId.Content other = numbering.content("c".repeat(64));
        PartId.Content second = numbering.content(A.toUpperCase());
        PartId.Position broken = numbering.position("/2/1");

        assertThat(first.ordinal()).isEqualTo(1);
        assertThat(second.ordinal()).isEqualTo(2);
        assertThat(other.ordinal()).isEqualTo(1);
        assertThat(second.sha256()).isEqualTo(A);
        assertThat(broken.containerSha256()).isEqualTo("b".repeat(64));
    }

    @Test
    void displayNameWithPathCharactersNeverBecomesIdOrPath() {
        String name = "../../etc/notiz.txt";
        EmbeddedEntry entry = new EmbeddedEntry(new PartId.Content(A, 1), Optional.of(A), Optional.of(name),
                Optional.of("text/plain"), "/1", Optional.empty(), 1, Optional.of(4L),
                Outcome.ok(new ReadExtent(4, 4)));
        DocumentKey key = DocumentKey.attachment(PAGE, "att1").child(entry.id());

        assertThat(entry.displayName()).contains(name);
        assertThat(entry.id().value()).doesNotContain("notiz").doesNotContain("..");
        assertThat(key.toString()).doesNotContain("notiz");
        assertThatThrownBy(() -> PartId.parse(name)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsUseZeroForNoLimitAndStartWithTheAdrDefaults() {
        ExtractionLimits defaults = ExtractionLimits.DEFAULTS;

        assertThat(defaults.maxFileBytes()).isZero();
        assertThat(defaults.maxChars()).isZero();
        assertThat(defaults.maxDepth()).isEqualTo(10);
        assertThat(defaults.maxEmbeddedFiles()).isEqualTo(10_000);
        assertThat(defaults.parallel()).isEqualTo(2);
        assertThat(defaults.memoryLimit("application/pdf")).isZero();
        assertThat(defaults.memoryLimit("message/rfc822")).isZero();
        assertThat(defaults.memoryLimit("application/zip")).isEqualTo(20L * 1024 * 1024);
        assertThat(defaults.memoryLimit("APPLICATION/PDF; charset=x")).isZero();
        assertThat(ExtractionLimits.exceeds(Long.MAX_VALUE, 0)).isFalse();
        assertThat(ExtractionLimits.exceeds(11, 10)).isTrue();
        assertThat(ExtractionLimits.exceeds(10, 10)).isFalse();
        assertThatThrownBy(() -> new ExtractionLimits(-1, 0, 0, 0, 0, 0, 0, 0, java.time.Duration.ZERO,
                java.time.Duration.ZERO, 0, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExtractionLimits(0, 0, 0, 0, 0, 0, 0, 0, java.time.Duration.ZERO,
                java.time.Duration.ZERO, 0, 0, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outerDocumentAndEachPartGetTheirOwnBlockStream() {
        List<String> events = new ArrayList<>();
        StructureSink sink = new StructureSink() {
            @Override
            public BlockSink document() {
                return stream("document");
            }

            @Override
            public PartSink part(EmbeddedEntry entry) {
                return stream(entry.id().value());
            }

            private PartSink stream(String name) {
                return new PartSink() {
                    @Override
                    public void head(DocumentHead head) {
                        events.add(name + ":head:" + head.subject().orElse(""));
                    }

                    @Override
                    public void accept(Block block) {
                        events.add(name + ":" + ((Block.Paragraph) block).text());
                    }

                    @Override
                    public void close() {
                        events.add(name + ":close");
                    }
                };
            }
        };
        DocumentStructure structure = new DocumentStructure() {
            @Override
            public String id() {
                return "test";
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public StructuredDocument extract(java.nio.file.Path original, String title, String mediaType,
                                              ExtractionLimits limits, StructureSink target) {
                BlockSink outer = target.document();
                outer.accept(new Block.Paragraph(0, Optional.empty(), List.of(), "Mail"));
                EmbeddedEntry entry = new EmbeddedEntry(new PartId.Content(A, 1), Optional.of(A), Optional.empty(),
                        Optional.of("text/plain"), "/1", Optional.empty(), 1, Optional.of(6L),
                        Outcome.ok(new ReadExtent(6, 6)));
                try (PartSink part = target.part(entry)) {
                    part.head(new DocumentHead(Optional.empty(), Optional.of("Weitergeleitet"), Optional.empty(),
                            List.of(), Optional.empty()));
                    part.accept(new Block.Paragraph(0, Optional.empty(), List.of(), "Anhang"));
                }
                outer.accept(new Block.Paragraph(1, Optional.empty(), List.of(), "Gruß"));
                outer.close();
                return new StructuredDocument(DocumentHead.EMPTY, Outcome.ok(new ReadExtent(100, 8)), List.of(entry),
                        2, 8);
            }
        };

        StructuredDocument result = structure.extract(java.nio.file.Path.of("x"), "x.eml", "message/rfc822",
                ExtractionLimits.DEFAULTS, sink);

        String part = "sha256:" + A + ":1";
        assertThat(DocumentStructure.MODEL_VERSION).isEqualTo("structured-document/v1");
        assertThat(events).containsExactly("document:Mail", part + ":head:Weitergeleitet", part + ":Anhang", part + ":close", "document:Gruß",
                "document:close");
        assertThat(result.blocks()).isEqualTo(2);
        assertThat(result.directory()).hasSize(1);
    }
}
