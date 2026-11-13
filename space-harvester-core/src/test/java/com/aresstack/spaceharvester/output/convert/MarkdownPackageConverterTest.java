package com.aresstack.spaceharvester.output.convert;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConversionNotice;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Entry;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Reference;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest.Status;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.tree.PageTree;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Markdown-Paket: Dokument-Anhänge liegen unter attachments/ und die Anhangslinks im Text zeigen darauf. */
class MarkdownPackageConverterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] PDF = "%PDF-1.4 Bericht".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SQL = "select 1;".getBytes(StandardCharsets.UTF_8);

    private static final String STORAGE = "<p>Siehe "
            + link("Bericht 2024.pdf", "den Bericht") + ", " + link("abfrage.sql", "")
            + " und " + link("verboten.docx", "Geheim") + " sowie " + link("nie gesehen.xlsx", "Tabelle") + ".</p>";

    private static final List<Entry> ENTRIES = List.of(
            document("att1", "Bericht 2024.pdf", Status.STORED, "sha256:pdf"),
            document("att2", "abfrage.sql", Status.STORED, "sha256:sql"),
            document("att3", "verboten.docx", Status.FORBIDDEN, null));

    private static String link(String file, String label) {
        return "<ac:link><ri:attachment ri:filename=\"" + file + "\" />" + (label.isEmpty() ? ""
                : "<ac:plain-text-link-body><![CDATA[" + label + "]]></ac:plain-text-link-body>") + "</ac:link>";
    }

    private static Entry document(String id, String file, Status status, String sha256) {
        return new Entry("100", Reference.document(id, file), id, "1", file, "application/octet-stream", 10, sha256,
                status, status == Status.STORED ? null : "forbidden");
    }

    @Test
    void attachmentsAreInThePackageAndLinkedRelatively() throws Exception {
        Map<String, byte[]> zip = unzip(convert(new Stored()));

        assertThat(zip.keySet()).containsExactly("pages.md", "attachments/100/Bericht_2024.pdf",
                "attachments/100/abfrage.sql");
        assertThat(zip.get("attachments/100/Bericht_2024.pdf")).isEqualTo(PDF);
        assertThat(zip.get("attachments/100/abfrage.sql")).isEqualTo(SQL);
        String markdown = new String(zip.get("pages.md"), StandardCharsets.UTF_8);
        assertThat(markdown).contains("[den Bericht](attachments/100/Bericht_2024.pdf)",
                        "[abfrage.sql](attachments/100/abfrage.sql)", "Geheim *(Anhang nicht im Paket)*",
                        "Tabelle *(Anhang nicht im Paket)*")
                .doesNotContain("attachment:");
    }

    @Test
    void withoutAttachmentsEveryLinkBecomesTextWithANotice() throws Exception {
        String markdown = new String(unzip(convert(ExportAssets.NONE)).get("pages.md"), StandardCharsets.UTF_8);

        assertThat(markdown).contains("den Bericht *(Anhang nicht im Paket)*").doesNotContain("attachment:");
    }

    @Test
    void relinkLeavesImagesToTheWriterAndReadsAngleBrackets() {
        MarkdownConverter.AttachmentLinks links = (page, file) -> file.equals("a b.pdf") ? "attachments/1/a_b.pdf"
                : null;

        assertThat(MarkdownConverter.relink("![Bild](<attachment:a b.pdf>) [Text \\[1\\]](<attachment:a b.pdf>)"
                + " [Weg](attachment:weg.pdf) [Web](https://example.org)", "1", links))
                .isEqualTo("![Bild](<attachment:a b.pdf>) [Text \\[1\\]](attachments/1/a_b.pdf)"
                        + " Weg *(Anhang nicht im Paket)* [Web](https://example.org)");
    }

    @Test
    void relinkLeavesCodeBlocksAndCodeSpansAsWritten() {
        MarkdownConverter.AttachmentLinks links = (page, file) -> file.equals("a.pdf") ? "attachments/1/a.pdf" : null;
        String code = "`[B](attachment:a.pdf)` und ``x ` [C](attachment:weg.pdf)``\n\n```\n[D](attachment:weg.pdf)\n```"
                + "\n\n> ```sql\n> [E](attachment:weg.pdf)\n> ```\n\n";

        assertThat(MarkdownConverter.relink(code + "[A](attachment:a.pdf) \\`[F](attachment:weg.pdf)", "1", links))
                .isEqualTo(code + "[A](attachments/1/a.pdf) \\`F *(Anhang nicht im Paket)*");
    }

    private static byte[] convert(ExportAssets assets) throws Exception {
        byte[] payload = JSON.writeValueAsBytes(JSON.createObjectNode().put("id", "100").set("body",
                JSON.createObjectNode().set("storage", JSON.createObjectNode().put("value", STORAGE))));
        PageSnapshot snapshot = page("wiki", "DEV", "100", null, payload);
        Input input = new Input(List.of(new ConvertiblePage(snapshot.header(), snapshot, 0)), assets,
                new ArrayList<>());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new MarkdownPackageConverter().convert(input, out);
        return out.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static final class Stored implements ExportAssets {

        @Override
        public Optional<ExportAssetManifest> manifest() {
            return Optional.of(new ExportAssetManifest(true, ENTRIES));
        }

        @Override
        public Optional<byte[]> content(Entry entry) {
            if (!entry.stored()) {
                return Optional.empty();
            }
            return Optional.of("sha256:pdf".equals(entry.sha256()) ? PDF : SQL);
        }
    }

    private record Input(List<ConvertiblePage> pages, ExportAssets assets, List<ConversionNotice> reported)
            implements ConversionInput {

        @Override
        public void report(ConversionNotice notice) {
            reported.add(notice);
        }

        @Override
        public String sourceId() {
            return "wiki";
        }

        @Override
        public String spaceKey() {
            return "DEV";
        }

        @Override
        public PageTree tree() {
            return new PageTree(List.of(), List.of(), pages.size(), 0);
        }

        @Override
        public List<PageHeader> headers() {
            return pages.stream().map(ConvertiblePage::header).toList();
        }

        @Override
        public void forEachPage(Consumer<ConvertiblePage> action) {
            pages.forEach(action);
        }
    }
}
