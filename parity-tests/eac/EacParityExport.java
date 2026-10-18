import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.ExportDocumentNode;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.KnowledgeExportUseCase;
import com.aresstack.enterpriseai.astu.acropolis.knowledge.PageNavigationUseCase;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceConfig;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceKnowledgeSource;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.exedra.document.ExportFormat;
import com.aresstack.enterpriseai.proasteion.exedra.document.ExportGraphics;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import com.aresstack.spaceharvester.parity.ParitySpace;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.function.BooleanSupplier;

/**
 * Exportiert den Paritäts-Space mit dem Enterprise AI Client, so wie dessen Navigation „Exportieren“ es tut:
 * {@link KnowledgeExportUseCase#subtree} ab dem Space und die Writer aus {@link ExportFormat}.
 *
 * <p>Nur für {@code generate.sh}; Java 8, übersetzt gegen den Klassenpfad des Enterprise AI Client. Schreibt
 * {@code parity.docx}, {@code parity.pdf}, {@code parity.md}, je Format die Liste der nicht eingebetteten Bilder
 * ({@code problems-<format>.txt}) und die Leseansicht je Seite ({@code reading/}).</p>
 */
public final class EacParityExport {

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        if (!out.isDirectory() && !out.mkdirs()) {
            throw new IllegalStateException("Ordner fehlt: " + out);
        }
        FakeConfluence confluence = ParitySpace.install(new FakeConfluence());
        SecretProvider none = new SecretProvider() {
            @Override
            public SecretMaterial resolve(SecretRef ref) throws SecretUnavailableException {
                throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_FOUND, ref, "keine");
            }
        };
        ConfluenceKnowledgeSource source = new ConfluenceKnowledgeSource(SourceAddress.of("parity"),
                ConfluenceConfig.builder(FakeConfluence.BASE).build(), confluence, none);
        NavigationNode space = source.position(KnowledgeResourceId.of("confluence",
                FakeConfluence.BASE.getHost() + FakeConfluence.BASE.getPath() + "/page/" + ParitySpace.HOME)).root();
        BooleanSupplier never = new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return false;
            }
        };
        ExportDocumentNode tree = new KnowledgeExportUseCase(new PageNavigationUseCase())
                .subtree(source, space, new KnowledgeExportUseCase.Progress() {
                    @Override
                    public void pageLoaded(int loaded, int found, String title) {
                    }
                }, never);
        write(tree, ExportFormat.DOCX, new File(out, "parity.docx"), new File(out, "problems-docx.txt"));
        write(tree, ExportFormat.PDF, new File(out, "parity.pdf"), new File(out, "problems-pdf.txt"));
        write(tree, ExportFormat.MARKDOWN, new File(out, "parity.md"), new File(out, "problems-md.txt"));
        readingViews(source, new File(out, "reading"));
    }

    /**
     * Leseansicht je Seite, wie der Lesebereich und die KI sie bekommen: Lesetext aus {@code load} nach
     * {@code reading/<seite>.txt}, Zahl der Bilder aus {@code pageView} nach {@code reading/images.properties}.
     */
    private static void readingViews(ConfluenceKnowledgeSource source, File out) throws Exception {
        if (!out.isDirectory() && !out.mkdirs()) {
            throw new IllegalStateException("Ordner fehlt: " + out);
        }
        PrintWriter images = new PrintWriter(new File(out, "images.properties"), "UTF-8");
        try {
            for (String page : ParitySpace.PAGES) {
                KnowledgeResourceId id = KnowledgeResourceId.of("confluence",
                        FakeConfluence.BASE.getHost() + FakeConfluence.BASE.getPath() + "/page/" + page);
                PrintWriter text = new PrintWriter(new File(out, page + ".txt"), "UTF-8");
                try {
                    text.print(source.load(id).text());
                } finally {
                    text.close();
                }
                images.println(page + "=" + source.pageView(id).images().size());
            }
        } finally {
            images.close();
        }
    }

    private static void write(ExportDocumentNode tree, ExportFormat format, File target, File problems)
            throws Exception {
        ExportGraphics graphics = ExportGraphics.standard();
        OutputStream stream = new FileOutputStream(target);
        try {
            format.writer(graphics).write(tree, stream);
        } finally {
            stream.close();
        }
        PrintWriter list = new PrintWriter(problems, "UTF-8");
        try {
            for (String problem : graphics.problems()) {
                list.println(problem);
            }
        } finally {
            list.close();
        }
    }
}
