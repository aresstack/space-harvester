package com.aresstack.spaceharvester.testcorpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.assets.DocumentText;
import com.aresstack.spaceharvester.source.DocumentFixtures;
import com.aresstack.spaceharvester.source.TikaDocumentText;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der Testkorpus selbst (ADR 0026, Paket TK): Die Erzeuger liefern, was ihre Beschreibung verspricht, in klein. */
class CorpusTest {

    private final DocumentText tika = new TikaDocumentText();

    @TempDir
    Path directory;

    @Test
    void mailsAreReproducibleAndReadableWithAllMarks() {
        assertThat(Corpus.duplicateNames(false).bytes()).isEqualTo(Corpus.duplicateNames(false).bytes());
        assertThat(Corpus.duplicateNames(true).bytes()).isNotEqualTo(Corpus.duplicateNames(false).bytes());

        String text = read("doppelt.eml", Corpus.duplicateNames(false).bytes());
        assertThat(text).contains(Corpus.MAIL_BODY_MARK, "MARKE-NOTIZ-1", "MARKE-NOTIZ-2", "MARKE-PFAD",
                "MARKE-FLUCHT", "MARKE-KOPIE");
        assertThat(read("doppelt-vertauscht.eml", Corpus.duplicateNames(true).bytes()))
                .contains("MARKE-NOTIZ-1", "MARKE-NOTIZ-2", "MARKE-KOPIE");
    }

    @Test
    void fileNamesWithPathCharactersAndDuplicatesStayAsTheyAre() {
        String raw = new String(Corpus.duplicateNames(false).bytes(), StandardCharsets.UTF_8);

        assertThat(count(raw, "filename=\"notiz.txt\"")).isEqualTo(2);
        assertThat(raw).contains("filename=\"ordner/unter.txt\"", "filename=\"../flucht.txt\"");
    }

    @Test
    void containersInMailsAreReadToTheInnermostPart() {
        assertThat(read("zip.eml", Corpus.zipInMail().bytes()))
                .contains(Corpus.MAIL_BODY_MARK, "MARKE-ZIP-TXT", "MARKE-ZIP-DOCX");
        assertThat(read("mail.eml", Corpus.mailInMail().bytes()))
                .contains(Corpus.MAIL_BODY_MARK, "MARKE-INNEN", "MARKE-INNEN-RTF", "MARKE-INNEN-XLSX");
        assertThat(read("msg.eml", Corpus.outlookMailInMail().bytes()))
                .contains(Corpus.MAIL_BODY_MARK, "MARKE-MSG-TEXT", "MARKE-MSG-DOCX", "MARKE-MSG-TXT");
    }

    @Test
    void theOutlookMessageIsAnOutlookMessageWithItsAttachments() {
        byte[] msg = Corpus.outlookMessage().bytes();

        assertThat(tika.detect("weiter.msg", msg)).contains(Mail.OUTLOOK);
        assertThat(read("weiter.msg", msg)).contains("Abstimmung Wartung", "MARKE-MSG-TEXT", "MARKE-MSG-DOCX",
                "MARKE-MSG-TXT");
    }

    @Test
    void damagedPartsDoNotHideTheHealthyOne() throws IOException {
        String text = read("defekt.eml", Corpus.damaged().bytes());

        assertThat(text).contains(Corpus.MAIL_BODY_MARK, "MARKE-GESUND", Archives.OPEN_MARK + "-1");
        assertThat(text).doesNotContain("MARKE-GEHEIM-PDF", "MARKE-GEHEIM-DOCX", "nie gelesen");
        List<Corpus.Attachment> damaged = Corpus.damagedAttachments();
        assertThat(damaged).extracting(Corpus.Attachment::fileName).contains("leer.pdf");
        assertThat(damaged.stream().filter(a -> a.fileName().equals("leer.pdf")).findFirst().orElseThrow().content())
                .isEmpty();
    }

    @Test
    void encryptedFilesNeedThePassword() throws IOException {
        byte[] pdf = DamagedFiles.encryptedPdf(DocumentFixtures.pdf("MARKE-GEHEIM-PDF"));
        assertThatThrownBy(() -> Loader.loadPDF(pdf)).isInstanceOf(InvalidPasswordException.class);
        try (PDDocument document = Loader.loadPDF(pdf, DamagedFiles.PASSWORD)) {
            assertThat(new PDFTextStripper().getText(document)).contains("MARKE-GEHEIM-PDF");
        }

        byte[] docx = DamagedFiles.encryptedOffice(DocumentFixtures.docx("MARKE-GEHEIM-DOCX"));
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(docx))) {
            assertThat(fs.getRoot().hasEntry("EncryptionInfo")).isTrue();
            assertThat(fs.getRoot().hasEntry("EncryptedPackage")).isTrue();
        }

        byte[] partly = Archives.partlyEncrypted();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(partly))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("offen-1.txt");
            assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).contains(Archives.OPEN_MARK + "-1");
            assertThatThrownBy(zip::getNextEntry).isInstanceOf(ZipException.class).hasMessageContaining("encrypted");
        }
        assertThat(new String(partly, StandardCharsets.ISO_8859_1)).contains(Archives.OPEN_MARK + "-2");
    }

    @Test
    void archivesHaveExactlyTheRequestedDepthAndCount() throws IOException {
        for (int depth : Limits.around(5)) {
            assertThat(depth(Archives.nested(depth))).isEqualTo(depth);
        }
        for (int count : Limits.around(100)) {
            assertThat(entries(Archives.many(count))).hasSize(count);
        }
        assertThat(entries(Archives.sameNameInFolders())).containsExactly("a/notiz.txt", "b/notiz.txt");
        assertThat(count(new String(Corpus.manyAttachments(7).bytes(), StandardCharsets.US_ASCII),
                "Content-Disposition: attachment")).isEqualTo(7);
        assertThat(count(new String(Corpus.nestedMails(4).bytes(), StandardCharsets.US_ASCII),
                "Content-Type: message/rfc822")).isEqualTo(3);
    }

    @Test
    void thePackedArchiveHasAHighRatioAndItsFollowingEntry() throws IOException {
        long unpacked = 8L * 1024 * 1024;
        byte[] zip = Archives.packed(unpacked);

        assertThat(unpacked / zip.length).isGreaterThan(400);
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry big = in.getNextEntry();
            assertThat(big.getName()).isEqualTo("gross.txt");
            assertThat(in.transferTo(OutputStream.nullOutputStream())).isEqualTo(unpacked);
            assertThat(in.getNextEntry().getName()).isEqualTo("danach.txt");
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).contains(Archives.AFTER_PACKED);
        }
        Path mail = Corpus.packedArchiveMail(directory.resolve("gepackt.eml"), unpacked);
        assertThat(Files.size(mail)).isLessThan(unpacked / 100);
    }

    @Test
    void aLargeAttachmentComesThroughTheMailByteForByte() throws Exception {
        byte[] content = new byte[3 * 1024 * 1024 + 17];
        new Random(1).nextBytes(content);
        Path attachment = Files.write(directory.resolve("gross.bin"), content);

        Path mail = Corpus.largeAttachmentMail(directory.resolve("gross.eml"), attachment, "unterlage.pdf",
                DocumentFixtures.PDF);

        String raw = Files.readString(mail, StandardCharsets.US_ASCII);
        assertThat(sha256(attachmentBytes(raw, "unterlage.pdf"))).isEqualTo(sha256(content));
        assertThat(raw).contains("filename=\"danach.txt\"");
    }

    @Test
    void largeFilesCarryTheirMarksOnEveryPageRowAndParagraph() throws IOException {
        Path images = LargeFiles.pdfWithImages(directory.resolve("bilder.pdf"), 3);
        assertThat(Files.size(images)).isGreaterThan(3L * 1000 * 1000);
        try (PDDocument document = Loader.loadPDF(images.toFile())) {
            assertThat(document.getNumberOfPages()).isEqualTo(3);
            assertThat(new PDFTextStripper().getText(document)).contains(LargeFiles.pageMark(1),
                    LargeFiles.pageMark(3));
        }
        try (PDDocument document = Loader.loadPDF(LargeFiles.pdfWithText(directory.resolve("text.pdf"), 2).toFile())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text.length()).isGreaterThan(2 * 2900);
            assertThat(text).contains(LargeFiles.pageMark(2));
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook(LargeFiles.xlsx(directory.resolve("k.xlsx"), 250).toFile())) {
            XSSFSheet sheet = workbook.getSheet("Kosten");
            assertThat(sheet.getLastRowNum()).isEqualTo(249);
            assertThat(sheet.getRow(249).getCell(0).getStringCellValue()).isEqualTo(LargeFiles.rowMark(249));
            assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) 9);
        } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
            throw new IOException(e);
        }
        try (InputStream in = Files.newInputStream(LargeFiles.docx(directory.resolve("w.docx"), 402, 2));
             XWPFDocument document = new XWPFDocument(in)) {
            assertThat(document.getAllPictures()).hasSize(2);
            assertThat(document.getTables()).hasSize(1);
            assertThat(document.getParagraphs()).filteredOn(p -> p.getText().endsWith(LargeFiles.paragraphMark(401)))
                    .hasSize(1);
            assertThat(document.getParagraphs()).filteredOn(p -> "Abschnitt 11".equals(p.getText())).hasSize(1);
        }
        Path again = LargeFiles.pdfWithImages(directory.resolve("noch.pdf"), 3);
        assertThat(Files.size(again)).isEqualTo(Files.size(images));
    }

    @Test
    void theStallingStreamDeliversThenStalls() throws IOException {
        byte[] content = new byte[100];
        InputStream in = Limits.stalling(new ByteArrayInputStream(content), 40, Duration.ofMillis(300));

        long started = System.nanoTime();
        assertThat(in.readNBytes(40)).hasSize(40);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(200));
        assertThat(in.readAllBytes()).hasSize(60);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    private String read(String fileName, byte[] content) {
        String mediaType = tika.detect(fileName, content).orElse("application/octet-stream");
        return tika.extract(fileName, mediaType, content).text();
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            n++;
        }
        return n;
    }

    private static List<String> entries(byte[] zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static int depth(byte[] zip) throws IOException {
        int deepest = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (entry.getName().endsWith(".zip")) {
                    deepest = Math.max(deepest, depth(in.readAllBytes()));
                }
            }
        }
        return deepest + 1;
    }

    /** Die base64-Bytes des Teils mit {@code filename="<name>"} bis zur nächsten Grenze. */
    private static byte[] attachmentBytes(String raw, String fileName) {
        Matcher part = Pattern.compile("filename=\"" + Pattern.quote(fileName) + "\"\r\n\r\n(.*?)\r\n--",
                Pattern.DOTALL).matcher(raw);
        assertThat(part.find()).isTrue();
        return Base64.getMimeDecoder().decode(part.group(1));
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
