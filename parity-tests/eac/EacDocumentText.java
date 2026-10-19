import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.DetectedContentType;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRegistry;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionRequest;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ExtractionResult;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ResourceExtractor;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.tika.DocumentExtraction;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Referenz für Typerkennung und Dokumenttext (#183): jede Datei aus {@code documents/} und die Referenzexporte
 * {@code parity.docx} und {@code parity.pdf} durch Erkennung und Extraktoren des Enterprise AI Client, wie
 * {@code DocumentSourceFileTextExtractor} sie für Confluence-Anhänge und „Lokale Dateien“ zusammenstellt
 * ({@code DocumentExtraction}: Markdown und Klartext direkt, sonst Apache Tika).
 *
 * <p>Schreibt {@code documents/types.properties} ({@code <datei>.type}: Typ nach Name und Inhalt, mit dem extrahiert
 * wird; {@code <datei>.content-type}: Typ nach dem Inhalt allein) und je Datei {@code documents/<datei>.txt}.</p>
 *
 * <pre>java EacDocumentText &lt;documents&gt; &lt;eac-referenzen&gt;</pre>
 */
public final class EacDocumentText {

    private static final int PREFIX_BYTES = 8192;

    public static void main(String[] args) throws Exception {
        File in = new File(args[0]);
        File references = new File(args[1]);
        File out = new File(references, "documents");
        if (!out.isDirectory() && !out.mkdirs()) {
            throw new IllegalStateException("Ordner fehlt: " + out);
        }
        List<File> files = new ArrayList<File>(Arrays.asList(in.listFiles()));
        files.add(new File(references, "parity.docx"));
        files.add(new File(references, "parity.pdf"));
        Collections.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        ContentDetector detector = DocumentExtraction.detector();
        ExtractionRegistry extractors = DocumentExtraction.registry();
        try (PrintWriter types = new PrintWriter(new File(out, "types.properties"), "UTF-8")) {
            for (File file : files) {
                String name = file.getName();
                byte[] content = Files.readAllBytes(file.toPath());
                byte[] prefix = Arrays.copyOf(content, Math.min(content.length, PREFIX_BYTES));
                DetectedContentType type = detector.detect(name, null, prefix);
                DetectedContentType byContent = detector.detect(null, null, prefix);
                types.println(name + ".type=" + type.mimeType());
                types.println(name + ".content-type=" + byContent.mimeType());
                ResourceExtractor extractor = extractors.findExtractor(type);
                if (extractor == null) {
                    throw new IllegalStateException(name + ": kein Extraktor für " + type.mimeType());
                }
                ExtractionResult result = extractor.extract(new ExtractionRequest(name, content, name, null, type));
                if (!result.isSuccess()) {
                    throw new IllegalStateException(name + ": " + result.errorMessage());
                }
                try (Writer text = new OutputStreamWriter(Files.newOutputStream(new File(out, name + ".txt").toPath()),
                        StandardCharsets.UTF_8)) {
                    text.write(result.document().combinedText());
                }
            }
        }
    }
}
