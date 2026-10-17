package com.aresstack.spaceharvester.nlp.malt;

import com.aresstack.spaceharvester.nlp.syntax.Component;
import com.aresstack.spaceharvester.nlp.syntax.DependencyParser;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.util.ArrayList;
import java.util.List;
import org.maltparser.concurrent.ConcurrentMaltParserModel;
import org.maltparser.core.exception.MaltChainedException;

/**
 * MaltParser hinter dem Port: übergibt je Satz die Wörter als CoNLL-U-Zeilen mit FORM und UPOS (die übrigen
 * Eingabespalten {@code _}, wie im Training aus #99) und übernimmt HEAD und DEPREL. {@code ConcurrentMaltParserModel}
 * ist threadsicher; das Modell wird einmal geladen und von allen Aufrufen geteilt.
 */
public final class MaltDependencyParser implements DependencyParser {

    private final ConcurrentMaltParserModel model;
    private final ModelManifest manifest;
    private final String mcoSha256;
    private final long loadMillis;

    MaltDependencyParser(ConcurrentMaltParserModel model, ModelManifest manifest, String mcoSha256, long loadMillis) {
        this.model = model;
        this.manifest = manifest;
        this.mcoSha256 = mcoSha256;
        this.loadMillis = loadMillis;
    }

    @Override
    public Sentence parse(Sentence tagged) {
        List<Token> tokens = tagged.tokens();
        String[] lines = new String[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.upos() == null) {
                throw new IllegalArgumentException("Wortart fehlt für Token " + token.id() + " (" + token.form()
                        + "); das Modell braucht FORM und UPOS");
            }
            lines[i] = token.id() + "\t" + clean(token.form()) + "\t_\t" + token.upos() + "\t_\t_";
        }
        String[] parsed;
        try {
            parsed = model.parseTokens(lines);
        } catch (MaltChainedException e) {
            throw new IllegalStateException("MaltParser konnte Satz " + (tagged.index() + 1) + " nicht parsen", e);
        }
        List<Token> result = new ArrayList<>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            String[] columns = parsed[i].split("\t", -1);
            int head = Integer.parseInt(columns[6]);
            result.add(tokens.get(i).withDependency(head, columns[7]));
        }
        return tagged.withTokens(result);
    }

    /** MaltParser trennt Spalten per Tab und erlaubt keine leeren Formen. */
    private static String clean(String form) {
        String cleaned = form.replace('\t', ' ').replace('\n', ' ');
        return cleaned.isEmpty() ? "_" : cleaned;
    }

    @Override
    public Component component() {
        return new Component("dependencies", "maltparser " + MaltModelLoader.MALTPARSER_VERSION,
                manifest.modelId() + " " + manifest.modelVersion(), mcoSha256);
    }

    /** Manifest des geladenen Modells (Labelset, Eingabespalten). */
    public ModelManifest manifest() {
        return manifest;
    }

    /** Dauer der Modellinitialisierung in Millisekunden. */
    public long loadMillis() {
        return loadMillis;
    }
}
