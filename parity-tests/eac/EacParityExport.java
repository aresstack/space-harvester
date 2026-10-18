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
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import com.aresstack.enterpriseai.source.api.PagePosition;
import com.aresstack.enterpriseai.source.api.SourceLink;
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
 * ({@code problems-<format>.txt}), die Leseansicht je Seite ({@code reading/}) und die Navigation
 * ({@code navigation.properties}).</p>
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
        navigation(source, new File(out, "navigation.properties"));
    }

    /**
     * Navigation je Seite als Seiten-IDs: {@code <seite>.children} (Kinder in Reihenfolge), {@code <seite>.ancestors}
     * (Breadcrumbs von oben, ohne Space), {@code <seite>.links} (ausgehende Seitenverweise), dazu {@code space.name}
     * und {@code space.children} (Wurzelseiten).
     */
    private static void navigation(ConfluenceKnowledgeSource source, File target) throws Exception {
        PrintWriter out = new PrintWriter(target, "UTF-8");
        try {
            PagePosition home = source.position(pageId(ParitySpace.HOME));
            out.println("space.name=" + home.root().title());
            out.println("space.children=" + children(source, home.root()));
            for (String page : ParitySpace.PAGES) {
                PagePosition position = source.position(pageId(page));
                StringBuilder ancestors = new StringBuilder();
                for (NavigationNode ancestor : position.ancestors()) {
                    ancestors.append(ancestors.length() == 0 ? "" : ",").append(id(ancestor.resource()));
                }
                StringBuilder links = new StringBuilder();
                for (SourceLink link : source.crossReferences(pageId(page)).outgoing()) {
                    String linked = id(link.target());
                    if (linked != null) {
                        links.append(links.length() == 0 ? "" : ",").append(linked);
                    }
                }
                out.println(page + ".children=" + children(source, position.page()));
                out.println(page + ".ancestors=" + ancestors);
                out.println(page + ".links=" + links);
            }
        } finally {
            out.close();
        }
    }

    private static String children(ConfluenceKnowledgeSource source, NavigationNode parent) throws Exception {
        StringBuilder ids = new StringBuilder();
        String cursor = null;
        NavigationChildren batch;
        do {
            batch = source.children(parent, cursor);
            for (NavigationNode child : batch.nodes()) {
                ids.append(ids.length() == 0 ? "" : ",").append(id(child.resource()));
            }
            cursor = batch.nextCursor();
        } while (!batch.complete());
        return ids.toString();
    }

    private static KnowledgeResourceId pageId(String page) {
        return KnowledgeResourceId.of("confluence",
                FakeConfluence.BASE.getHost() + FakeConfluence.BASE.getPath() + "/page/" + page);
    }

    /** Seiten-ID aus der Ressource ({@code …/page/<id>}), sonst {@code null}. */
    private static String id(KnowledgeResourceId resource) {
        if (resource == null) {
            return null;
        }
        String value = resource.value();
        int at = value.lastIndexOf("/page/");
        return at < 0 ? null : value.substring(at + "/page/".length());
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
                KnowledgeResourceId id = pageId(page);
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
